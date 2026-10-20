package ee.schimke.ha.client.design

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class TemplateUsageTest {

  private var day = "2026-10-20"
  private var sharing = true
  private val store = InMemoryUsageStore()
  private val usage = TemplateUsage(store, sharing = { sharing }, today = { day })
  private val agent = UsageUserAgent(version = "0.1.48", platform = "android", surface = "phone")

  private val tile = UsageKey("tile", "default", "lcd", UsageSource.Remote)
  private val heading = UsageKey("heading", "default", "lcd", UsageSource.Local)

  @Test
  fun defaultIsOptIn() {
    assertFalse(UsageSharing.DEFAULT_SHARES)
    assertFalse(UsageSharing.Default.effective())
    assertEquals(UsageSharing.Default, UsageSharing.parse(null))
    assertEquals(UsageSharing.Default, UsageSharing.parse("garbage"))
  }

  @Test
  fun flippingTheDefaultNeverOverridesAnExplicitChoice() {
    for (defaultShares in listOf(false, true)) {
      assertTrue(UsageSharing.Enabled.effective(defaultShares))
      assertFalse(UsageSharing.Disabled.effective(defaultShares))
      assertEquals(defaultShares, UsageSharing.Default.effective(defaultShares))
    }
  }

  @Test
  fun theSwitchAlwaysWritesAnExplicitChoice() {
    assertEquals(UsageSharing.Enabled, UsageSharing.chosen(true))
    assertEquals(UsageSharing.Disabled, UsageSharing.chosen(false))
    assertEquals(UsageSharing.Disabled, UsageSharing.parse(UsageSharing.Disabled.name))
  }

  @Test
  fun bucketsHideExactCounts() {
    assertEquals(
      listOf("1", "2-5", "2-5", "6-20", "6-20", "21+"),
      listOf(1, 2, 5, 6, 20, 21).map(TemplateUsage::bucketOf),
    )
  }

  @Test
  fun aCardCountsOnceADay_andOnlyFinishedDaysAreReported() = runTest {
    usage.record(tile, "card-a")
    usage.record(tile, "card-a")
    usage.record(tile, "card-b")
    usage.record(heading, "card-c")
    // Today is still counting.
    assertTrue(usage.pending("Terrazzo", "0.1.48", "android").isEmpty())

    day = "2026-10-21"
    val reports = usage.pending("Terrazzo", "0.1.48", "android")
    assertEquals(1, reports.size)
    assertEquals("2026-10-20", reports.single().day)
    assertEquals(
      listOf(
        UsageRow("heading", "default", "lcd", UsageSource.Local, "1"),
        UsageRow("tile", "default", "lcd", UsageSource.Remote, "2-5"),
      ),
      reports.single().rows,
    )
  }

  @Test
  fun nothingIsCountedWhileSharingIsOff() = runTest {
    sharing = false
    usage.record(tile, "card-a")
    assertNull(store.ledger)
  }

  @Test
  fun turningSharingOffDeletesPendingCounts_andUploadsNothing() = runTest {
    usage.record(tile, "card-a")
    day = "2026-10-21"
    sharing = false
    val requests = mutableListOf<HttpRequestData>()
    val client =
      HttpClient(
        MockEngine {
          requests += it
          respond("")
        }
      )
    assertEquals(0, usage.upload(client, "https://designs.test/designs/v1", agent))
    assertTrue(requests.isEmpty())
    assertNull(store.ledger)
  }

  @Test
  fun uploadSendsBucketedDays_withTheFixedUserAgent_andForgetsThem() = runTest {
    usage.record(tile, "card-a")
    day = "2026-10-21"
    val requests = mutableListOf<HttpRequestData>()
    val client =
      HttpClient(
        MockEngine {
          requests += it
          respond("")
        }
      )
    assertEquals(1, usage.upload(client, "https://designs.test/designs/v1/", agent))

    val request = requests.single()
    assertEquals("/designs/v1/usage", request.url.encodedPath)
    assertEquals("Terrazzo/0.1.48 (android; surface=phone)", request.headers[HttpHeaders.UserAgent])
    val body = (request.body as TextContent).text
    val report = Json.decodeFromString(UsageReport.serializer(), body)
    assertEquals("2026-10-20", report.day)
    assertEquals(listOf(UsageRow("tile", "default", "lcd", UsageSource.Remote, "1")), report.rows)
    // Nothing from the cards themselves leaves the device.
    assertFalse("card-a" in body)
    assertTrue(usage.pending("Terrazzo", "0.1.48", "android").isEmpty())
  }

  @Test
  fun aFailedUploadKeepsTheDay() = runTest {
    usage.record(tile, "card-a")
    day = "2026-10-21"
    val client = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
    assertEquals(0, usage.upload(client, "https://designs.test/designs/v1", agent))
    assertEquals(1, usage.pending("Terrazzo", "0.1.48", "android").size)
  }

  @Test
  fun aRejectedReportIsDropped_soNewerDaysStillGo() = runTest {
    usage.record(tile, "card-a")
    day = "2026-10-21"
    usage.record(heading, "card-b")
    day = "2026-10-22"
    val sentDays = mutableListOf<String>()
    val client =
      HttpClient(
        MockEngine { request ->
          val report =
            Json.decodeFromString(UsageReport.serializer(), (request.body as TextContent).text)
          // The server refuses the oldest day for good (e.g. older than it accepts).
          if (report.day == "2026-10-20") respondError(HttpStatusCode.BadRequest)
          else {
            sentDays += report.day
            respond("")
          }
        }
      )
    assertEquals(1, usage.upload(client, "https://designs.test/designs/v1", agent))
    assertEquals(listOf("2026-10-21"), sentDays)
    assertTrue(usage.pending("Terrazzo", "0.1.48", "android").isEmpty())
  }

  @Test
  fun rateLimitedOrTimedOutUploadsAreRetried() {
    assertTrue(TemplateUsage.isPermanentRejection(HttpStatusCode.BadRequest))
    assertTrue(TemplateUsage.isPermanentRejection(HttpStatusCode.PayloadTooLarge))
    assertFalse(TemplateUsage.isPermanentRejection(HttpStatusCode.TooManyRequests))
    assertFalse(TemplateUsage.isPermanentRejection(HttpStatusCode.RequestTimeout))
    assertFalse(TemplateUsage.isPermanentRejection(HttpStatusCode.ServiceUnavailable))
  }

  @Test
  fun surfacesAreCountedApart_andThePhoneStaysOutOfTheJson() = runTest {
    usage.record(tile, "card-a")
    usage.record(tile.copy(surface = UsageSurfaces.WIDGET), "card-a")
    day = "2026-10-21"
    val rows = usage.pending("Terrazzo", "0.1.48", "android").single().rows
    assertEquals(listOf(UsageSurfaces.PHONE, UsageSurfaces.WIDGET), rows.map { it.surface })
    // The phone's rows read exactly as before surfaces, so an older server still takes them.
    val phone = Json.encodeToString(UsageRow.serializer(), rows.first())
    assertFalse("surface" in phone)
    assertEquals(
      UsageSurfaces.PHONE,
      Json.decodeFromString(UsageKey.serializer(), Json.encodeToString(UsageKey.serializer(), tile))
        .surface,
    )
  }

  @Test
  fun utcDayIsTheCivilDate() {
    assertEquals("1970-01-01", TemplateUsage.utcDay(0))
    assertEquals("1969-12-31", TemplateUsage.utcDay(-1))
    assertEquals("2024-02-29", TemplateUsage.utcDay(1_709_208_000_000L)) // 12:00Z
    assertEquals("2026-10-20", TemplateUsage.utcDay(1_792_497_600_000L))
    assertEquals("2026-10-20", TemplateUsage.utcDay(1_792_540_799_999L)) // 23:59:59.999Z
    assertEquals("2000-03-01", TemplateUsage.utcDay(951_868_800_000L))
  }

  @Test
  fun aBrowserUploadSendsNoUserAgentHeader() = runTest {
    usage.record(tile, "card-a")
    day = "2026-10-21"
    val agents = mutableListOf<String?>()
    val client =
      HttpClient(
        MockEngine {
          agents += it.headers[HttpHeaders.UserAgent]
          respond("")
        }
      )
    assertEquals(
      1,
      usage.upload(client, "https://designs.test/designs/v1", agent, sendUserAgentHeader = false),
    )
    assertFalse(agents.single().orEmpty().startsWith("Terrazzo/"))
  }

  @Test
  fun aRecordWaitingOnTheLockNeverOutlivesTurningSharingOff() = runTest {
    // clear() holds the lock (its delete suspends) while a record() that saw sharing on waits.
    val deleting = CompletableDeferred<Unit>()
    val gated =
      object : UsageStore by store {
        override suspend fun delete() {
          deleting.await()
          store.delete()
        }
      }
    val usage = TemplateUsage(gated, sharing = { sharing }, today = { day })
    val clearing = launch { usage.clear() }
    testScheduler.runCurrent()
    val recording = launch { usage.record(tile, "card-a") }
    testScheduler.runCurrent()
    sharing = false // The switch flips, then its clear completes.
    deleting.complete(Unit)
    clearing.join()
    recording.join()
    assertNull(store.ledger)
  }

  @Test
  fun designRequestsCarryTheFixedUserAgent() = runTest {
    val agents = mutableListOf<String?>()
    val key =
      DesignKey(
        cardType = "tile",
        surface = DesignSurface.MobileApp,
        rcApiLevel = 7,
        shape = "domain=light",
        theme = DesignKey.bakedTheme("TerrazzoHome", dark = false),
      )
    val files =
      DesignPublisher.publish(
          listOf(BuiltDesign(key, slots = 1, bytes = "rc".encodeToByteArray())),
          serial = 1,
          nowEpochMs = 1_000,
        )
        .files
    val client =
      HttpClient(
        MockEngine { request ->
          agents += request.headers[HttpHeaders.UserAgent]
          respond(files.getValue(request.url.encodedPath.removePrefix("/v1/")))
        }
      )
    val repository =
      DesignRepository(
        DesignServerConfig(
          baseUrl = "https://designs.test/v1",
          allowUnsigned = true,
          userAgent = agent,
        ),
        InMemoryDesignStore(),
        client,
      ) {
        2_000
      }
    repository.refresh()
    assertEquals(2, agents.size)
    assertTrue(agents.all { it == "Terrazzo/0.1.48 (android; surface=phone)" })
  }
}
