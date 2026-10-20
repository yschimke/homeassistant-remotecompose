package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.client.design.UsageReport
import ee.schimke.ha.client.design.UsageRow
import ee.schimke.ha.client.design.UsageSource
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class UsageStatsTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = DesignKey.styledTheme("lcd", true),
    )
  // 2026-10-20T12:00:00Z
  private var now = 1_792_497_600_000L
  private val agent = "Terrazzo/0.1.48 (android; surface=phone)"

  private fun library(): Pair<File, String> {
    val dir = Files.createTempDirectory("designs").toFile()
    val output =
      DesignPublisher.publish(listOf(BuiltDesign(key, 1, template)), serial = 5, nowEpochMs = 1_000)
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    return dir to output.manifest.designs.single().sha256
  }

  private fun report(day: String = "2026-10-19", bucket: String = "2-5") =
    UsageReport(
      day = day,
      app = "Terrazzo",
      appVersion = "0.1.48",
      platform = "android",
      rows = listOf(UsageRow("tile", "default", "lcd", UsageSource.Remote, bucket)),
    )

  @Test
  fun theUserAgentIsReducedToAKnownApp_neverStoredFreeForm() {
    assertEquals(ClientAgent("Terrazzo", "0.1.48", "android", "phone"), ClientAgent.parse(agent))
    assertEquals(ClientAgent.OTHER, ClientAgent.parse("Mozilla/5.0 (X11; Linux) id=1234").app)
    assertEquals("", ClientAgent.parse("Mozilla/5.0 (X11; Linux) id=1234").version)
    assertEquals(ClientAgent.OTHER, ClientAgent.parse(null).app)
  }

  @Test
  fun countsUnderKAnonymityAreNeverShown() {
    val stats = UsageStats(null, clock = { now })
    repeat(4) { stats.manifestFetched(agent) }
    assertTrue(stats.rows("2026-10-01", "2026-10-31").isEmpty())
    stats.manifestFetched(agent)
    assertEquals(5, stats.rows("2026-10-01", "2026-10-31").single().count)
  }

  @Test
  fun daysSurviveARestart_andExpireAfterRetention() {
    val dir = Files.createTempDirectory("usage").toFile()
    val first = UsageStats(dir, clock = { now })
    repeat(5) { first.reportReceived(report()) }
    first.flush()
    assertTrue(File(dir, "2026-10-19.json").isFile)
    val text = File(dir, "2026-10-19.json").readText()
    assertFalse("127.0.0.1" in text)

    val second = UsageStats(dir, clock = { now })
    assertEquals(5, second.rows("2026-10-19", "2026-10-19").single().count)

    now += (UsageStats.RETENTION_DAYS + 2) * 86_400_000
    second.flush()
    assertFalse(File(dir, "2026-10-19.json").exists())
  }

  @Test
  fun reportsMustBeExactlyTheBucketedSchema() {
    val today = "2026-10-20"
    val good = Json.encodeToString(UsageReport.serializer(), report())
    assertTrue(UsageReportCheck.parse(good, today).isSuccess)
    // An unknown field (say, an entity id) is refused whole.
    val extra = good.replaceFirst("{", """{"entityId":"light.kitchen",""")
    assertTrue(UsageReportCheck.parse(extra, today).isFailure)
    val exact = Json.encodeToString(UsageReport.serializer(), report(bucket = "3"))
    assertTrue(UsageReportCheck.parse(exact, today).isFailure)
    val future = Json.encodeToString(UsageReport.serializer(), report(day = "2026-10-21"))
    assertTrue(UsageReportCheck.parse(future, today).isFailure)
    val name =
      Json.encodeToString(
        UsageReport.serializer(),
        report()
          .copy(rows = listOf(UsageRow("tile", "Kitchen Light", "lcd", UsageSource.Remote, "1"))),
      )
    assertTrue(UsageReportCheck.parse(name, today).isFailure)
  }

  @Test
  fun theServerCountsFetchesAndReports_andShowsThemOnlyToStatsUsers() = testApplication {
    val (dir, sha) = library()
    val stats = UsageStats(null, clock = { now })
    val http = createClient { followRedirects = false }
    val github =
      GithubAuth(
        clientId = "id",
        clientSecret = "secret",
        publicUrl = "https://ha.example",
        sessionSecret = "session-secret",
        http = http,
      )
    application {
      designServer(LibraryHolder(dir), github = github, usage = stats, statsUsers = setOf("yuri"))
    }
    val client = createClient { followRedirects = false }
    repeat(5) {
      client.get("$API_BASE/manifest.json") { header(HttpHeaders.UserAgent, agent) }
      client.get("$API_BASE/blobs/$sha.rc") { header(HttpHeaders.UserAgent, agent) }
      val posted =
        client.post("$API_BASE/usage") {
          contentType(ContentType.Application.Json)
          setBody(Json.encodeToString(UsageReport.serializer(), report()))
        }
      assertEquals(HttpStatusCode.NoContent, posted.status)
    }
    val bad =
      client.post("$API_BASE/usage") {
        contentType(ContentType.Application.Json)
        setBody("""{"day":"2026-10-19"}""")
      }
    assertEquals(HttpStatusCode.BadRequest, bad.status)

    val rows = stats.rows("2026-10-01", "2026-10-31")
    val fetch = rows.single { it.cell.kind == UsageStats.FETCH }.cell
    assertEquals(
      listOf("tile", "default", "lcd", "android", "phone"),
      listOf(fetch.cardType, fetch.variant, fetch.style, fetch.platform, fetch.surface),
    )
    assertEquals(3, rows.size)

    // Signed out: sign in. Signed in but not a stats user: not found. A stats user: the page.
    assertEquals(HttpStatusCode.Found, client.get("/stats/templates").status)
    val other = "design-server-session=" + github.sign("octocat|${Long.MAX_VALUE}")
    assertEquals(
      HttpStatusCode.NotFound,
      client.get("/stats/templates") { header(HttpHeaders.Cookie, other) }.status,
    )
    val yuri = "design-server-session=" + github.sign("yuri|${Long.MAX_VALUE}")
    val page = client.get("/stats/templates") { header(HttpHeaders.Cookie, yuri) }
    assertEquals(HttpStatusCode.OK, page.status)
    assertTrue("Fetched templates" in page.bodyAsText())
    val api = client.get("/api/stats/templates") { header(HttpHeaders.Cookie, yuri) }
    assertTrue("\"render\"" in api.bodyAsText())
  }

  @Test
  fun rowsCarryTheirSurface_andAReportWithoutOneIsFromThePhone() {
    val today = "2026-10-20"
    // A report from before surfaces (no "surface" field) still parses, as the phone's.
    val old =
      """{"day":"2026-10-19","app":"Terrazzo","appVersion":"0.1.48","platform":"android",""" +
        """"rows":[{"cardType":"tile","variant":"default","style":"lcd","source":"Remote",""" +
        """"bucket":"1"}]}"""
    assertEquals("phone", UsageReportCheck.parse(old, today).getOrThrow().rows.single().surface)
    val widget =
      report()
        .copy(rows = listOf(UsageRow("tile", "default", "lcd", UsageSource.Local, "1", "widget")))
    val parsed =
      UsageReportCheck.parse(Json.encodeToString(UsageReport.serializer(), widget), today)
        .getOrThrow()
    val stats = UsageStats(null, clock = { now })
    repeat(5) { stats.reportReceived(parsed) }
    assertEquals("widget", stats.rows("2026-10-01", "2026-10-31").single().cell.surface)
    val bad = widget.copy(rows = listOf(widget.rows.single().copy(surface = "Living Room")))
    assertTrue(
      UsageReportCheck.parse(Json.encodeToString(UsageReport.serializer(), bad), today).isFailure
    )
  }

  @Test
  fun aBrowserAppNamesItselfInTheClientParameter() {
    val web = "Terrazzo/0.1.51 (web; surface=web)"
    assertEquals(web, ClientAgent.of("Mozilla/5.0 (X11; Linux)", web))
    // A real app User-Agent wins over the parameter; neither gives the browser's, counted as other.
    assertEquals(agent, ClientAgent.of(agent, web))
    assertEquals(ClientAgent.OTHER, ClientAgent.parse(ClientAgent.of("Mozilla/5.0", null)).app)
  }

  @Test
  fun webAppsCanPostReports_andTagTheirBlobFetches() = testApplication {
    val (dir, sha) = library()
    val stats = UsageStats(null, clock = { now })
    application { designServer(LibraryHolder(dir), usage = stats) }
    val preflight =
      client.options("$API_BASE/usage") {
        header(HttpHeaders.Origin, "https://homeassistant.local:8123")
        header(HttpHeaders.AccessControlRequestMethod, "POST")
        header(HttpHeaders.AccessControlRequestHeaders, "content-type")
      }
    assertEquals(HttpStatusCode.NoContent, preflight.status)
    assertEquals("*", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
    val posted =
      client.post("$API_BASE/usage") {
        header(HttpHeaders.Origin, "https://homeassistant.local:8123")
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(UsageReport.serializer(), report()))
      }
    assertEquals(HttpStatusCode.NoContent, posted.status)
    assertEquals("*", posted.headers[HttpHeaders.AccessControlAllowOrigin])
    repeat(5) {
      client.get(
        "$API_BASE/blobs/$sha.rc?client=" +
          java.net.URLEncoder.encode("Terrazzo/0.1.51 (web; surface=web)", "UTF-8")
      )
    }
    val fetch = stats.rows("2026-10-01", "2026-10-31").single { it.cell.kind == UsageStats.FETCH }
    assertEquals(listOf("web", "web"), listOf(fetch.cell.platform, fetch.cell.surface))
  }

  @Test
  fun withoutStatsUsersThereIsNoStatsPage() = testApplication {
    val (dir, _) = library()
    application { designServer(LibraryHolder(dir), usage = UsageStats(null)) }
    assertEquals(HttpStatusCode.NotFound, client.get("/stats/templates").status)
  }
}
