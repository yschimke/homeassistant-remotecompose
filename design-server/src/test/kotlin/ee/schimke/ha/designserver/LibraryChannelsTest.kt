package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Publishing libraries without redeploying (card-design-server.md, "Publishing"): a store of
 * channels seeded from the image, CI uploads to `main` and to each pull request's preview, only the
 * files the server lacks, and edits to the library's designs in UI Builder published as they save.
 */
class LibraryChannelsTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val light = key("baked:Material3:light")
  private val dark = key("baked:Material3:dark")

  private fun key(theme: String) =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = theme,
    )

  /** A library directory, generated at [generatedAt], optionally with a second design. */
  private fun library(generatedAt: Long = 1_000, withDark: Boolean = true): File {
    val dir = Files.createTempDirectory("library").toFile()
    val designs =
      listOfNotNull(
        BuiltDesign(light, 1, template),
        BuiltDesign(dark, 1, template).takeIf { withDark },
      )
    val output = DesignPublisher.publish(designs, serial = generatedAt, nowEpochMs = generatedAt)
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    File(dir, "${DesignLibrary.SOURCES}/tile.uid")
      .also { it.parentFile.mkdirs() }
      .writeText("""{"id":"tile","revision":0,"nodes":{}}""")
    return dir
  }

  private fun store() = Files.createTempDirectory("store").toFile()

  /** [dir] as a zip, with only [only] (paths) when given, and [index] as `files.json`. */
  private fun zip(
    dir: File,
    only: Set<String>? = null,
    index: Map<String, String>? = null,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      dir
        .walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
          val path = file.relativeTo(dir).path
          if (only != null && path !in only) return@forEach
          zip.putNextEntry(ZipEntry(path))
          file.inputStream().use { it.copyTo(zip) }
          zip.closeEntry()
        }
      if (index != null) {
        zip.putNextEntry(ZipEntry(LibraryChannels.INDEX))
        zip.write(
          Json.encodeToString(
              JsonObject.serializer(),
              JsonObject(mapOf("files" to JsonObject(index.mapValues { JsonPrimitive(it.value) }))),
            )
            .encodeToByteArray()
        )
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun indexOf(dir: File): Map<String, String> =
    dir
      .walkTopDown()
      .filter { it.isFile }
      .associate { it.relativeTo(dir).path to LibraryChannels.sha256(it) }

  private fun serve(
    channels: LibraryChannels,
    token: String? = "ci-token",
    block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    application { designServer(channels.main, channels = channels, publishToken = token) }
    block()
  }

  @Test
  fun mainIsSeededFromTheImageAndStampedByTheServer() {
    var now = 50_000L
    val channels =
      LibraryChannels(store(), library(generatedAt = 1_000), ttlSeconds = 600, clock = { now })
    val manifest = channels.main.current().manifest
    assertEquals(50_000, manifest.serial, "the server's own serial, not the build's")
    assertEquals(600, manifest.ttlSeconds)
    assertEquals(1_000, manifest.generatedAtEpochMs, "when it was built")
    assertEquals("image", channels.list().single().published?.origin)
  }

  @Test
  fun aNewerImageReplacesAnOlderPublishAndNeverANewerOne() {
    val root = store()
    var now = 10_000L
    LibraryChannels(root, library(generatedAt = 1_000), clock = { now })
    // CI published a library built at 5 000 since.
    now = 20_000
    val first = LibraryChannels(root, library(generatedAt = 1_000), clock = { now })
    val ci = library(generatedAt = 5_000, withDark = false)
    first.install(LibraryPaths.MAIN, first.stagingDir().also { ci.copyRecursively(it, true) }, "ci")
    assertEquals(1, first.main.current().designs.size)

    // An image built before that publish keeps it; one built after replaces it.
    now = 30_000
    assertEquals(
      1,
      LibraryChannels(root, library(generatedAt = 4_000), clock = { now })
        .main
        .current()
        .designs
        .size,
    )
    now = 40_000
    val newer = LibraryChannels(root, library(generatedAt = 6_000), clock = { now })
    assertEquals(2, newer.main.current().designs.size)
    assertTrue(newer.main.current().manifest.serial >= 40_000)
  }

  @Test
  fun aPullRequestIsPublishedToItsOwnChannelAndShownUntilItCloses() {
    val channels = LibraryChannels(store(), library())
    serve(channels) {
      val bundle = zip(library(generatedAt = 2_000, withDark = false))
      val published =
        client.put(
          "$LIBRARY_CHANNELS_PATH/pr-42?commit=abc1234def&title=Wrap+markdown&branch=agent%2Fmd&url=https%3A%2F%2Fgithub.com%2Fx%2Fy%2Fpull%2F42"
        ) {
          header(HttpHeaders.Authorization, "Bearer ci-token")
          setBody(bundle)
        }
      assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())

      // The app plays it from the channel's own base; main is untouched.
      val manifest = Json {
        ignoreUnknownKeys = true
      }
        .decodeFromString(
          DesignManifest.serializer(),
          client.get("$API_BASE/channels/pr-42/manifest.json").bodyAsText(),
        )
      assertEquals(1, manifest.designs.size)
      val sha = manifest.designs.single().sha256
      assertEquals(HttpStatusCode.OK, client.get("$API_BASE/channels/pr-42/blobs/$sha.rc").status)
      assertEquals(2, channels.main.current().designs.size)

      // The browser lists it as in flight, and shows which pull request a page previews.
      val browser = client.get("/designs").bodyAsText()
      assertTrue("In-flight pull requests" in browser, browser)
      assertTrue("#42 Wrap markdown" in browser)
      val preview = client.get("/designs?channel=pr-42").bodyAsText()
      assertTrue(
        "Preview: <a href=\"https://github.com/x/y/pull/42\">#42 Wrap markdown</a>" in preview,
        preview,
      )
      assertTrue("abc1234def" in preview)
      assertTrue("$API_BASE/channels/pr-42" in preview, "the app URL that plays it")
      val id = DesignLibrary.idOf(light)
      assertTrue("/designs/$id?channel=pr-42" in preview, "its designs link within the channel")
      assertEquals(HttpStatusCode.OK, client.get("/designs/$id?channel=pr-42").status)
      val listed = Json.parseToJsonElement(client.get(LIBRARY_CHANNELS_PATH).bodyAsText()).jsonArray
      assertEquals(
        listOf("main", "pr-42"),
        listed.map { it.jsonObject["channel"]!!.jsonPrimitive.content },
      )

      // Closed: gone from the app and the browser.
      val deleted =
        client.delete("$LIBRARY_CHANNELS_PATH/pr-42") {
          header(HttpHeaders.Authorization, "Bearer ci-token")
        }
      assertEquals(HttpStatusCode.NoContent, deleted.status)
      assertEquals(
        HttpStatusCode.NotFound,
        client.get("$API_BASE/channels/pr-42/manifest.json").status,
      )
      assertEquals(HttpStatusCode.NotFound, client.get("/designs?channel=pr-42").status)
      assertFalse("In-flight pull requests" in client.get("/designs").bodyAsText())
    }
  }

  @Test
  fun onlyTheTokenPublishesAndMainIsNeverDeleted() {
    val channels = LibraryChannels(store(), library())
    serve(channels) {
      val bundle = zip(library())
      assertEquals(
        HttpStatusCode.Unauthorized,
        client
          .put("$LIBRARY_CHANNELS_PATH/main") {
            header(HttpHeaders.Authorization, "Bearer wrong")
            setBody(bundle)
          }
          .status,
      )
      assertEquals(
        HttpStatusCode.BadRequest,
        client
          .put("$LIBRARY_CHANNELS_PATH/feature-x") {
            header(HttpHeaders.Authorization, "Bearer ci-token")
            setBody(bundle)
          }
          .status,
        "only main and pr-<n>",
      )
      assertEquals(
        HttpStatusCode.BadRequest,
        client
          .delete("$LIBRARY_CHANNELS_PATH/main") {
            header(HttpHeaders.Authorization, "Bearer ci-token")
          }
          .status,
      )
    }
    serve(channels, token = null) {
      assertEquals(
        HttpStatusCode.NotFound,
        client.put("$LIBRARY_CHANNELS_PATH/main") { setBody(zip(library())) }.status,
        "no token: no publishing",
      )
    }
  }

  @Test
  fun aPublishSendsOnlyWhatTheServerLacks() {
    val channels = LibraryChannels(store(), library())
    serve(channels) {
      // The same designs plus a source the server hasn't seen.
      val next = library(generatedAt = 3_000)
      File(next, "${DesignLibrary.SOURCES}/gauge.uid").writeText("""{"id":"gauge"}""")
      val index = indexOf(next)
      val missing =
        Json.decodeFromString(
          ListSerializer(String.serializer()),
          client
            .post("$LIBRARY_CHANNELS_PATH/missing") {
              header(HttpHeaders.Authorization, "Bearer ci-token")
              setBody(
                Json.encodeToString(ListSerializer(String.serializer()), index.values.toList())
              )
            }
            .bodyAsText(),
        )
      val upload = index.filterValues { it in missing }.keys
      assertTrue(
        "blobs/${DesignLibrary.load(next).manifest.designs.first().sha256}.rc" !in upload,
        "blobs it holds stay",
      )
      assertTrue("sources/gauge.uid" in upload)
      assertTrue("manifest.json" in upload, "stamped per install, so always sent")

      val published =
        client.put("$LIBRARY_CHANNELS_PATH/pr-9") {
          header(HttpHeaders.Authorization, "Bearer ci-token")
          setBody(zip(next, only = upload, index = index))
        }
      assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())
      assertNotNull(channels.holder("pr-9")?.current()?.sources?.get("gauge"))
      assertEquals(2, channels.holder("pr-9")!!.current().designs.size)
    }
  }

  @Test
  fun refusesABundleThatLeavesTheLibraryOrNamesFilesItLacks() {
    val channels = LibraryChannels(store(), library())
    val escape =
      ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
          zip.putNextEntry(ZipEntry("../evil.txt"))
          zip.write(1)
          zip.closeEntry()
        }
      }
    assertFailsWith<IllegalArgumentException> {
      channels.unpack(ByteArrayInputStream(escape.toByteArray()))
    }
    val next = library(generatedAt = 3_000)
    val index = indexOf(next) + ("blobs/${"0".repeat(64)}.rc" to "1".repeat(64))
    assertFailsWith<IllegalArgumentException> {
      channels.unpack(ByteArrayInputStream(zip(next, index = index)))
    }
  }

  @Test
  fun aLibraryDerivedFromAnOlderMainIsNotInstalled() {
    val channels = LibraryChannels(store(), library())
    val served = channels.main.current().manifest.serial
    channels.install(
      LibraryPaths.MAIN,
      channels.stagingDir().also { library(5_000).copyRecursively(it, true) },
      "ci",
    )
    assertFailsWith<LibraryChannels.StaleLibrary> {
      channels.install(
        LibraryPaths.MAIN,
        channels.stagingDir().also { library(6_000).copyRecursively(it, true) },
        "edit:tile",
        expectSerial = served,
      )
    }
  }

  @Test
  fun anEditInUiBuilderIsPublishedToMainOnceAndCopiedIntoItsSource() = testApplication {
    val channels = LibraryChannels(store(), library())
    val stored = mutableMapOf<String, JsonObject>()
    externalServices {
      hosts("http://uib") {
        routing {
          get("/api/ui-builder/v1/designs/{id}") {
            val document =
              stored[call.parameters["id"]!!]
                ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
            call.respondText(Json.encodeToString(JsonObject.serializer(), document))
          }
          put("/api/ui-builder/v1/designs/{id}") {
            stored[call.parameters["id"]!!] = Json.parseToJsonElement(call.receiveText()).jsonObject
            call.respondText("created", status = HttpStatusCode.Created)
          }
        }
      }
    }
    val config =
      UiBuilderConfig(apiUrl = "http://uib", browserUrl = "https://builder.example", "secret")
    val integration = UiBuilderIntegration(config, UiBuilderClient(config, createClient {}))
    val sources = LibrarySources(channels.main, integration)
    val rebuilt = mutableListOf<String>()
    val publisher =
      LibraryEditPublisher(channels, sources) { source, target, cardType, document, previews ->
        // Stands in for the capture: the same designs, the edited source.
        rebuilt += "$cardType:$previews"
        source.copyRecursively(target)
        File(target, LibraryChannels.PUBLISHED).delete()
        File(target, "${DesignLibrary.SOURCES}/tile.uid").writeText(document.toString())
        2
      }
    application {}
    startApplication()
    runBlocking {
      assertEquals(emptyList(), publisher.syncOnce(), "nothing edited yet")

      stored["library-tile"] =
        Json.parseToJsonElement("""{"id":"library-tile","revision":3,"nodes":{"x":{}}}""")
          .jsonObject
      assertEquals(listOf("tile"), publisher.syncOnce())
      val main = channels.main.current()
      assertEquals("3", main.sources["tile"]!!["revision"]!!.jsonPrimitive.content)
      assertEquals("edit:tile", channels.list().first().published?.origin)

      // Published: the library now says it was drawn from that edit, so there's nothing to do.
      assertEquals(emptyList(), publisher.syncOnce())
      assertEquals(listOf("tile:false", "tile:true"), rebuilt, "designs first, then previews")
    }
    assertNull(channels.holder("pr-1"))
  }
}
