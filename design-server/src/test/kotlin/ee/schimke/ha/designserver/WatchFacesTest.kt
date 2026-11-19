package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcplayer.protocol.referencesContinuousSystemVariable
import ee.schimke.composeai.rcplayer.protocol.referencesMovingSystemVariable
import ee.schimke.ha.client.design.DesignPublisher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WatchFacesTest {
  private val site = "https://ui.coo.ee"

  /** wear-os-samples' SimpleAnalog, packed as a Watch Face Push APK's `res/` would hold it. */
  private val apk: ByteArray = run {
    val root = File(checkNotNull(javaClass.getResource("/watchfaces/SimpleAnalog")).toURI())
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zip.write(BINARY_XML)
      root
        .walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.path }
        .forEach { file ->
          zip.putNextEntry(ZipEntry("res/" + file.relativeTo(root).invariantSeparatorsPath))
          zip.write(file.readBytes())
        }
      // aapt2 compiles `res/xml/`: binary XML the translator can't read, skipped and counted.
      zip.putNextEntry(ZipEntry("res/xml/watch_face_info.xml"))
      zip.write(BINARY_XML)
    }
    out.toByteArray()
  }

  private fun listing(sha: String = hex(apk), apkUrl: String = "$site/apk/simple") =
    """{"designs":[{"id":"simple_analog","title":"Simple analog","version":3,"wffVersion":2,
      |"packageName":"ee.schimke.watchface.push.watchfacepush.simple_analog",
      |"apkUrl":"$apkUrl","sha256":"$sha","sizeBytes":${apk.size},"validationToken":"t"}]}"""
      .trimMargin()

  private class FakeSite(var listing: String, val apk: ByteArray) : WatchFaces.Fetcher {
    val fetched = mutableListOf<String>()

    override suspend fun get(url: String, maxBytes: Long): ByteArray? {
      fetched += url
      return when {
        url.endsWith(WatchFaces.DESIGNS_PATH) -> listing.encodeToByteArray()
        url.endsWith("/apk/simple") -> apk
        else -> null
      }
    }
  }

  private fun card(face: String = "simple_analog", server: String? = null, size: Int? = null) =
    buildJsonObject {
      put("type", "watchface")
      put("face", face)
      server?.let { put("server", it) }
      size?.let { put("size", it) }
    }

  @Test
  fun aPublishedFaceCompilesToADocumentOfItsSize() = runBlocking {
    val fake = FakeSite(listing(), apk)
    val faces = WatchFaces(fake)
    val match = assertNotNull(faces.resolve(card(size = 300)))

    assertEquals("watchface:simple_analog", match.design)
    assertTrue(match.exact)
    val bytes = assertNotNull(WatchFaceBlobs.blob(match.sha256))
    assertEquals(hex(bytes), match.sha256)
    val document = RcDocumentCodec.decode(bytes, RcWireLimits())
    assertTrue(document.operations.isNotEmpty())
    assertEquals("300", match.key.shape.substringAfter("px="))
    // Time comes from the player's clock; the face's own values are answered with its defaults.
    assertTrue("dwf.is_ambient" in match.bindings, match.bindings.keys.toString())
    assertEquals("0", match.bindings.getValue("dwf.is_ambient").content)
    assertTrue(match.notes.any { "1 compiled" in it }, match.notes.toString())

    // Compiled once per published version: a second card asks the site for nothing new.
    val again = assertNotNull(faces.resolve(card(size = 300)))
    assertEquals(match.sha256, again.sha256)
    assertEquals(1, fake.fetched.count { it.endsWith("/apk/simple") })
  }

  @Test
  fun withoutSweepAFaceNeedsAFrameOnlyOnceASecond() = runBlocking {
    val faces = WatchFaces(FakeSite(listing(), apk))
    fun document(match: CardMatch) =
      RcDocumentCodec.decode(assertNotNull(WatchFaceBlobs.blob(match.sha256)), RcWireLimits())

    // SimpleAnalog's second hand sweeps: as authored, the face reads the continuous clock.
    val authored = document(assertNotNull(faces.resolve(card())))
    assertTrue(authored.referencesContinuousSystemVariable())
    // A wall panel's face ticks: it still reads the clock, but only whole seconds.
    val panel = assertNotNull(faces.resolve(card(), smallPlayer = true))
    assertTrue(panel.key.shape.endsWith(";tick"), panel.key.shape)
    assertTrue(document(panel).referencesMovingSystemVariable())
    assertTrue(!document(panel).referencesContinuousSystemVariable())
    // The card can say otherwise either way.
    val swept = buildJsonObject {
      put("type", "watchface")
      put("face", "simple_analog")
      put("sweep", true)
    }
    assertTrue(
      document(assertNotNull(faces.resolve(swept, smallPlayer = true)))
        .referencesContinuousSystemVariable(),
    )
  }

  @Test
  fun aFaceCanDropItsSecondHand() = runBlocking {
    val faces = WatchFaces(FakeSite(listing(), apk))
    val withHand = assertNotNull(faces.resolve(card()))
    val without = buildJsonObject {
      put("type", "watchface")
      put("face", "simple_analog")
      put("seconds", false)
    }
    val match = assertNotNull(faces.resolve(without))
    assertTrue(match.key.shape.endsWith(";no-seconds"), match.key.shape)
    assertTrue(match.sha256 != withHand.sha256)
    // Sweeping second hands are the face's only continuous-clock readers.
    val document =
      RcDocumentCodec.decode(assertNotNull(WatchFaceBlobs.blob(match.sha256)), RcWireLimits())
    assertTrue(!document.referencesContinuousSystemVariable())
    assertEquals(
      "<AnalogClock></AnalogClock>",
      WatchFaces.withoutSecondHands(
        "<AnalogClock><SecondHand x=\"1\"><Sweep/></SecondHand></AnalogClock>",
      ),
    )
  }

  @Test
  fun aFaceWithoutSecondsPlaysAsAnAndroid17LauncherWidget() = runBlocking {
    // What watchface-tools' Faces phone app asks for: ticking, no second hand, its widget's size.
    val widget = buildJsonObject {
      put("type", "watchface")
      put("face", "simple_analog")
      put("size", 416)
      put("sweep", false)
      put("seconds", false)
    }
    val match = assertNotNull(WatchFaces(FakeSite(listing(), apk)).resolve(widget))
    val bytes = assertNotNull(WatchFaceBlobs.blob(match.sha256))
    fun fold(profile: String) =
      assertNotNull(
        PlayerFolding.fold(
          bytes,
          PlayerFolding.parsePlayer(buildJsonObject { put("profile", profile) }),
          match.bindings.keys,
        ),
      )
    // API 37's launcher player plays it as is; Android 16's has no DRAW_TO_BITMAP.
    assertEquals(emptyList(), fold("widgets-v7").notes)
    assertTrue(fold("widgets-v6").notes.any { "DRAW_TO_BITMAP" in it })
  }

  @Test
  fun sweepElementsAreRemovedWhateverTheirForm() {
    val xml =
      """
      |<SecondHand><Sweep frequency="15" /><Sweep>
      |</Sweep ><Tick duration="0.2"/></SecondHand>
      """
        .trimMargin()
    assertEquals("<SecondHand><Tick duration=\"0.2\"/></SecondHand>", WatchFaces.withoutSweep(xml))
  }

  @Test
  fun anApkThatDoesntMatchItsListingIsRefused() = runBlocking {
    val faces = WatchFaces(FakeSite(listing(sha = "0".repeat(64)), apk))
    val problem = assertFailsWith<WatchFaces.Problem> { faces.resolve(card()) }
    assertEquals(HttpStatusCode.BadGateway, problem.status)
  }

  @Test
  fun facesComeOnlyFromTheListedSites() = runBlocking {
    val fake = FakeSite(listing(apkUrl = "https://elsewhere.example/apk/simple"), apk)
    val faces = WatchFaces(fake)
    assertEquals(
      HttpStatusCode.BadRequest,
      assertFailsWith<WatchFaces.Problem> { faces.resolve(card(server = "https://evil.example")) }
        .status,
    )
    assertEquals(
      HttpStatusCode.BadRequest,
      assertFailsWith<WatchFaces.Problem> { faces.resolve(card(server = "http://ui.coo.ee")) }
        .status,
    )
    // A listed site naming another host's APK: never downloaded.
    assertEquals(
      HttpStatusCode.BadGateway,
      assertFailsWith<WatchFaces.Problem> { faces.resolve(card()) }.status,
    )
    assertTrue(fake.fetched.none { "elsewhere" in it || "evil" in it }, fake.fetched.toString())
  }

  @Test
  fun theHttpFetcherStopsAtItsCapAndNeverFollowsARedirect() = runBlocking {
    val requested = mutableListOf<String>()
    val engine = MockEngine { request ->
      requested += request.url.toString()
      when (request.url.encodedPath) {
        "/big" -> respond(ByteArray(64 * 1024), HttpStatusCode.OK)
        "/moved" ->
          respond(
            "",
            HttpStatusCode.Found,
            headersOf(HttpHeaders.Location, "https://internal.example/secret"),
          )
        else -> respond("ok", HttpStatusCode.OK)
      }
    }
    val fetcher = WatchFaces.httpFetcher(HttpClient(engine) { followRedirects = false })
    assertEquals("ok", fetcher.get("$site/small", 1024)?.decodeToString())
    assertFailsWith<WatchFaces.Problem> { fetcher.get("$site/big", 1024) }
    assertNull(fetcher.get("$site/moved", 1024))
    assertTrue(requested.none { "internal.example" in it }, requested.toString())
  }

  @Test
  fun serverAddressesAreNormalised() {
    assertEquals(site, WatchFaces.normalize("ui.coo.ee"))
    assertEquals(site, WatchFaces.normalize(" https://UI.coo.ee:443/ "))
    assertNull(WatchFaces.normalize("https://ui.coo.ee/x"))
    assertNull(WatchFaces.normalize("https://u:p@ui.coo.ee"))
    assertEquals(listOf("https://a.example"), WatchFaces.serversFrom("a.example, ,http://b"))
    assertEquals(WatchFaces.DEFAULT_SERVERS, WatchFaces.serversFrom(null))
  }

  @Test
  fun anOtherCardIsNotAWatchFace() = runBlocking {
    assertNull(
      WatchFaces(FakeSite(listing(), apk)).resolve(buildJsonObject { put("type", "tile") }),
    )
  }

  @Test
  fun theResolveRouteServesTheFaceAndItsBlob() = testApplication {
    val dir = Files.createTempDirectory("designs").toFile()
    for ((path, bytes) in DesignPublisher.publish(emptyList(), serial = 1, nowEpochMs = 1).files) {
      File(dir, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }
    application {
      designServer(LibraryHolder(dir), watchFaces = WatchFaces(FakeSite(listing(), apk)))
    }
    val body = buildJsonObject { put("card", card()) }.toString()
    val resolved = client.post("/api/cards/resolve") { setBody(body) }
    assertEquals(HttpStatusCode.OK, resolved.status, resolved.bodyAsText())
    val match = Json.parseToJsonElement(resolved.bodyAsText()).jsonObject
    val blob = client.get(match.getValue("blob").jsonPrimitive.content)
    assertEquals(HttpStatusCode.OK, blob.status)
    assertEquals(match.getValue("sha256").jsonPrimitive.content, hex(blob.readRawBytes()))

    // A small player (the wall panel) gets the face folded for it, under its own sha.
    val panel = buildJsonObject {
      put("card", card())
      put("player", buildJsonObject { put("profile", "kobento-mcu") })
    }
      .toString()
    val folded = client.post("/api/cards/resolve") { setBody(panel) }
    assertEquals(HttpStatusCode.OK, folded.status, folded.bodyAsText())
    val foldedSha =
      Json.parseToJsonElement(folded.bodyAsText()).jsonObject.getValue("sha256").jsonPrimitive
    assertNotNull(client.get("/designs/v1/blobs/${foldedSha.content}.rc").readRawBytes())

    val missing =
      client.post("/api/cards/resolve") {
        setBody(buildJsonObject { put("card", card(face = "nope")) }.toString())
      }
    assertEquals(HttpStatusCode.NotFound, missing.status)
  }

  @Test
  fun withoutWatchFacesTheRouteSaysSo() = testApplication {
    val dir = Files.createTempDirectory("designs").toFile()
    for ((path, bytes) in DesignPublisher.publish(emptyList(), serial = 1, nowEpochMs = 1).files) {
      File(dir, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }
    application { designServer(LibraryHolder(dir)) }
    val response =
      client.post("/api/cards/resolve") {
        setBody(buildJsonObject { put("card", card()) }.toString())
      }
    assertEquals(HttpStatusCode.NotFound, response.status)
    assertContentEquals(
      "this server doesn't draw watch faces".encodeToByteArray(),
      response.readRawBytes(),
    )
  }

  private companion object {
    /** A binary XML chunk header, as aapt2 writes a compiled XML file. */
    val BINARY_XML = byteArrayOf(3, 0, 8, 0, 8, 0, 0, 0)

    fun hex(bytes: ByteArray) =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
