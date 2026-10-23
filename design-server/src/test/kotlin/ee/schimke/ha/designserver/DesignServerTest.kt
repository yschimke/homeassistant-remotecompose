package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignCatalogEntry
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignExample
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignPreviews
import ee.schimke.ha.design.DesignQuery
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.design.DesignTags
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The design server over a small library: one real template (a light tile, captured by
 * `:card-designs`) in two themes, and a catalog whose example card carries markup to escape.
 */
class DesignServerTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val light = key("baked:Material3:light")
  private val dark = key("baked:Material3:dark")
  private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

  private fun key(theme: String) =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = theme,
    )

  /**
   * Writes a library to a temp dir: [catalog] adds catalog.json and previews, [extra] more designs
   * (with no catalog entry).
   */
  private fun library(
    catalog: Boolean = true,
    serial: Long = 1,
    extra: List<BuiltDesign> = emptyList(),
  ): File {
    val dir = Files.createTempDirectory("designs").toFile()
    val output =
      DesignPublisher.publish(
        listOf(BuiltDesign(light, 1, template), BuiltDesign(dark, 1, template)) + extra,
        serial = serial,
        nowEpochMs = 1_000,
      )
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    if (catalog) {
      val entries =
        listOf(light, dark).map { key ->
          val id = DesignLibrary.idOf(key)
          DesignCatalogEntry(
            id = id,
            sha256 = output.manifest.designs.first().sha256,
            key = key,
            example =
              DesignExample(
                "fixture:tile",
                buildJsonObject {
                  put("type", "tile")
                  put("entity", "light.kitchen")
                  put("name", "<script>alert(1)</script>")
                },
              ),
            previews =
              DesignPreviews(
                example = DesignCatalog.previewPath(id, "example"),
                template = DesignCatalog.previewPath(id, "template"),
              ),
          )
        }
      for (entry in entries) {
        File(dir, entry.previews.example!!).also { it.parentFile.mkdirs() }.writeBytes(png)
      }
      File(dir, DesignCatalog.PATH)
        .writeText(
          Json.encodeToString(DesignCatalog.serializer(), DesignCatalog(serial, 1_000, entries))
        )
    }
    return dir
  }

  private fun serve(dir: File, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application { designServer(LibraryHolder(dir)) }
    block()
  }

  @Test
  fun servesTheManifestAndRevalidatesByEtag() =
    serve(library()) {
      val response = client.get("$API_BASE/manifest.json")
      assertEquals(HttpStatusCode.OK, response.status)
      val manifest = Json.decodeFromString(DesignManifest.serializer(), response.bodyAsText())
      assertEquals(2, manifest.designs.size)
      val etag = assertNotNull(response.headers[HttpHeaders.ETag])
      assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])

      val again = client.get("$API_BASE/manifest.json") { header(HttpHeaders.IfNoneMatch, etag) }
      assertEquals(HttpStatusCode.NotModified, again.status)
    }

  @Test
  fun manifestCarriesEveryTagAClientFiltersBy() {
    val console = key(DesignKey.styledTheme("console", dark = true)).copy(cardType = "gauge")
    serve(library(extra = listOf(BuiltDesign(console, 1, template)))) {
      val manifest =
        Json.decodeFromString(
          DesignManifest.serializer(),
          client.get("$API_BASE/manifest.json").bodyAsText(),
        )
      val tags = DesignTags.of(manifest.designs)
      assertEquals(mapOf("classic" to 2, "console" to 1), tags.style)
      assertEquals(mapOf("gauge" to 1, "tile" to 2), tags.cardType)
      assertEquals(mapOf(DesignEntry.BUILTIN_AUTHOR to 3), tags.author)
      assertEquals(
        listOf(console),
        manifest.designs.filter(DesignQuery(style = "Console")::matches).map { it.key },
      )
    }
  }

  @Test
  fun servesBlobsImmutablyAndNothingElse() =
    serve(library()) {
      val sha = DesignLibrary.load(library()).manifest.designs.first().sha256
      val blob = client.get("$API_BASE/blobs/$sha.rc")
      assertEquals(HttpStatusCode.OK, blob.status)
      assertContentEquals(template, blob.bodyAsBytes())
      assertTrue(blob.headers[HttpHeaders.CacheControl]!!.contains("immutable"))

      assertEquals(
        HttpStatusCode.NotFound,
        client.get("$API_BASE/blobs/${"0".repeat(64)}.rc").status,
      )
      assertEquals(HttpStatusCode.NotFound, client.get("$API_BASE/blobs/..%2Fmanifest.json").status)
      assertEquals(
        HttpStatusCode.NotFound,
        client.get("$API_BASE/previews/..%2Fmanifest.json").status,
      )
    }

  @Test
  fun servesTheEmbedToAnyOrigin() =
    serve(library()) {
      // Pages on other origins import the embed as a module and fetch the library's files.
      val sha = DesignLibrary.load(library()).manifest.designs.first().sha256
      for (path in listOf("$API_BASE/manifest.json", "$API_BASE/blobs/$sha.rc")) {
        assertEquals("*", client.get(path).headers[HttpHeaders.AccessControlAllowOrigin], path)
      }
      for (name in listOf("ha-card.js", "rc-player.js")) {
        val script = client.get("/embed/v1/$name")
        assertEquals(HttpStatusCode.OK, script.status, name)
        assertEquals("*", script.headers[HttpHeaders.AccessControlAllowOrigin], name)
        assertTrue(script.headers[HttpHeaders.ContentType]!!.startsWith("text/javascript"), name)
      }
      assertTrue(
        client.get("/embed/v1/ha-card.js").bodyAsText().contains("customElements.define('ha-card'")
      )
      assertTrue(client.get("/embed/v1/rc-player.js").bodyAsText().contains("RcdPlayer"))
      assertTrue(client.get("/embed/v1/demo.html").bodyAsText().contains("<ha-card"))
      assertEquals(HttpStatusCode.NotFound, client.get("/embed/v1/index.html").status)
      assertEquals(HttpStatusCode.NotFound, client.get("/embed/v1/..%2F..%2Flogback.xml").status)
    }

  @Test
  fun refusesALibraryWhoseBlobDoesntMatchItsManifest() {
    val dir = library()
    val sha = DesignLibrary.load(dir).manifest.designs.first().sha256
    File(dir, DesignManifest.blobPath(sha)).writeBytes(template + 0)
    assertFailsWith<IllegalStateException> { DesignLibrary.load(dir) }
  }

  @Test
  fun browserListsDesignsAndEscapesExamples() =
    serve(library()) {
      val page = client.get("/designs?style=all&mode=all").bodyAsText()
      assertTrue("2 designs" in page, "stats")
      assertTrue(page.contains("domain</b> light"), "properties show as chips")
      assertTrue("$API_BASE/previews/" in page, "previews link to the library")

      val id = DesignLibrary.idOf(light)
      val detail = client.get("/designs/$id").bodyAsText()
      assertTrue("light.s0.name" in detail, "bindings are decoded from the template")
      assertTrue("valid" in detail, "the template is in the mobile-app op set")
      assertFalse("<script>alert" in detail, "example cards are escaped")
      assertTrue("&lt;script&gt;" in detail)
      assertTrue("/designs/${DesignLibrary.idOf(dark)}" in detail, "links its other theme")
    }

  @Test
  fun browserDefaultsToOneThemeAndFilters() =
    serve(library()) {
      // Unfiltered, the first page shows each shape once: the first theme, light.
      assertTrue("1 shown" in client.get("/designs").bodyAsText())
      assertTrue("2 shown" in client.get("/designs?style=all&mode=all").bodyAsText())
      assertTrue("0 shown" in client.get("/designs?type=gauge").bodyAsText())
    }

  @Test
  fun consoleUsesTheSameDesignInBothModes() {
    val console = key(DesignKey.styledTheme("console", dark = true))
    serve(library(extra = listOf(BuiltDesign(console, 1, template)))) {
      for (mode in listOf("light", "dark", "all")) {
        val response = client.get("/designs?type=all&style=console&mode=$mode&q=&preview=example")
        assertEquals(HttpStatusCode.OK, response.status)
        val page = response.bodyAsText()
        assertTrue("1 shown" in page)
        assertTrue("/designs/${DesignLibrary.idOf(console)}" in page)
        assertFalse("No designs match." in page)
        assertTrue("<option value=\"$mode\" selected>" in page)
        val api =
          Json.parseToJsonElement(client.get("/api/designs?style=console&mode=$mode").bodyAsText())
            .jsonObject
        assertEquals(1, api["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(
          DesignLibrary.idOf(console),
          api["designs"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content,
        )
      }
      assertTrue("1 shown" in client.get("/designs?style=console").bodyAsText())
      assertTrue("2 shown" in client.get("/designs?style=all&mode=light").bodyAsText())
      assertTrue("2 shown" in client.get("/designs?style=all&mode=dark").bodyAsText())
      assertTrue(
        "1 shown" in
          client
            .get("/designs?type=tile&style=console&mode=light&q=domain%3Dlight&preview=template")
            .bodyAsText()
      )
      assertTrue(
        "0 shown" in client.get("/designs?type=gauge&style=console&mode=light").bodyAsText()
      )
      assertTrue(
        "0 shown" in client.get("/designs?style=console&mode=light&q=missing").bodyAsText()
      )
      assertTrue("0 shown" in client.get("/designs?style=console&mode=sepia").bodyAsText())
    }
    // Even a console-only library must offer both host modes in the selector.
    val dir = library(extra = listOf(BuiltDesign(console, 1, template)))
    val loaded = DesignLibrary.load(dir)
    val manifest =
      loaded.manifest.copy(designs = loaded.manifest.designs.filter { it.key == console })
    File(dir, "manifest.json").writeText(Json.encodeToString(DesignManifest.serializer(), manifest))
    serve(dir) {
      val page = client.get("/designs?style=console&mode=light").bodyAsText()
      assertTrue("1 shown" in page)
      assertTrue("<option value=\"light\" selected>" in page)
      assertTrue("<option value=\"dark\">" in page)
    }
  }

  @Test
  fun modeRecoveryPreservesOtherFiltersAndEscapesLinks() {
    val nixie = key(DesignKey.styledTheme("nixie", dark = true))
    serve(library(extra = listOf(BuiltDesign(nixie, 1, template)))) {
      val page =
        client
          .get("/designs?type=tile&style=nixie&mode=light&q=domain%3Dlight&preview=template")
          .bodyAsText()
      val recovery =
        "/designs?type=tile&amp;style=nixie&amp;mode=dark&amp;q=domain%3Dlight&amp;preview=template"
      assertTrue("href=\"$recovery\">dark</a>" in page)
      assertTrue("1 shown" in client.get(recovery.replace("&amp;", "&")).bodyAsText())

      for (query in
        listOf(
          "style=Material3&mode=light&q=does-not-exist",
          "style=unknown&mode=light",
          "type=gauge",
        )) {
        val empty = client.get("/designs?$query").bodyAsText()
        assertTrue("No designs match." in empty)
        assertTrue("Clear all filters" in empty)
        assertFalse("Available modes:" in empty, "ordinary empty results are not a mode mismatch")
      }
    }
  }

  @Test
  fun frontPageSellsTheAppAndLinksTheDesigner() =
    serve(library()) {
      val page = client.get("/").bodyAsText()
      assertTrue("<h1>Terrazzo</h1>" in page)
      assertTrue("href=\"#home-assistant\"" in page)
      assertTrue("id=\"home-assistant\"" in page)
      assertTrue(
        page.indexOf("id=\"designer\"") < page.indexOf("id=\"home-assistant\""),
        "the app and its cards come first; the integration is the last section",
      )
      assertTrue("Optional, for wall panels" in page, "the integration reads as optional")
      assertTrue("The app needs none of this" in page, "the app stands alone")
      assertTrue("href=\"/demo/\"" in page, "the demo is one tap from the hero")
      assertTrue("/blob/main/docs/HOME_ASSISTANT.md\">Installation and updates" in page)
      assertTrue("my.home-assistant.io/redirect/hacs_repository/" in page, "opens HACS")
      assertTrue("redirect/config_flow_start/?domain=terrazzo" in page, "starts the setup")
      assertFalse("configuration.yaml" in page, "sets up in the UI, not YAML")
      assertTrue("/terrazzo?dashboard=" in page, "says where the dashboard opens")
      assertTrue("dashboard=wall-panel&amp;wall" in page, "offers the wall-panel preset")
      assertTrue(
        "/releases/latest/download/terrazzo-home-assistant.zip" in page,
        "offers the manual ZIP",
      )
      assertTrue("href=\"${SiteConfig.DEFAULT_DESIGNER_URL}\"" in page, "links the card designer")
      assertTrue("href=\"${SiteConfig.DEFAULT_CATALOG_URL}\"" in page, "links the catalog")
      assertTrue(
        "href=\"https://play.google.com/store/apps/details?id=ee.schimke.harc\">Get the app" in
          page,
        "sends Get the app to the Play listing",
      )
      assertEquals(
        2,
        page.split("https://play.google.com/store/apps/details?id=ee.schimke.harc").size - 1,
        "the app is the call to action in the hero and again after the three steps",
      )
      assertTrue("href=\"/designs\"" in page, "links the template browser")
      assertTrue("$API_BASE/previews/" in page, "teases with the library's cards")
      assertEquals(
        listOf(light.theme),
        Landing.teaser(DesignLibrary.load(library())).map { it.key.theme },
        "one design per card type, in light",
      )

      val shot = client.get("/site/${Landing.SCREENSHOTS.first().file}")
      assertEquals(HttpStatusCode.OK, shot.status)
      assertContentEquals(png, shot.bodyAsBytes().copyOf(png.size), "a bundled PNG")
      assertEquals(HttpStatusCode.NotFound, client.get("/site/feature.png").status)
    }

  @Test
  fun oldBrowserLinksRedirect() =
    serve(library()) {
      val noFollow = createClient { followRedirects = false }
      val response = noFollow.get("/?type=tile&style=all")
      assertEquals(HttpStatusCode.MovedPermanently, response.status)
      assertEquals("/designs?type=tile&style=all", response.headers[HttpHeaders.Location])
      val campaign = noFollow.get("/?utm_source=readme")
      assertEquals(HttpStatusCode.OK, campaign.status, "other queries stay on the front page")
      assertTrue("<h1>Terrazzo</h1>" in campaign.bodyAsText())
    }

  @Test
  fun apiDescribesDesigns() =
    serve(library()) {
      val list =
        Json.parseToJsonElement(client.get("/api/designs?mode=dark").bodyAsText()).jsonObject
      assertEquals(1, list["count"]!!.jsonPrimitive.content.toInt())

      val detail =
        Json.parseToJsonElement(
            client.get("/api/designs/${DesignLibrary.idOf(light)}").bodyAsText()
          )
          .jsonObject
      val bindings =
        detail["bindings"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
      assertEquals(listOf("light.s0.is_on", "light.s0.name", "light.s0.state"), bindings)
      assertEquals(JsonPrimitive("light"), detail["properties"]!!.jsonObject["domain"])
      assertEquals(HttpStatusCode.NotFound, client.get("/api/designs/nope").status)
    }

  @Test
  fun servesOnlyCatalogPreviews() =
    serve(library()) {
      val path = DesignCatalog.previewPath(DesignLibrary.idOf(light), "example")
      assertContentEquals(png, client.get("$API_BASE/$path").bodyAsBytes())
      // The catalog names a template preview that was never written: not found, not an error.
      val missing = DesignCatalog.previewPath(DesignLibrary.idOf(light), "template")
      assertEquals(HttpStatusCode.NotFound, client.get("$API_BASE/$missing").status)
    }

  @Test
  fun servesALibraryWithoutACatalog() =
    serve(library(catalog = false)) {
      assertEquals(HttpStatusCode.OK, client.get("$API_BASE/manifest.json").status)
      assertEquals(HttpStatusCode.NotFound, client.get("$API_BASE/catalog.json").status)
      val page = client.get("/designs/${DesignLibrary.idOf(light)}").bodyAsText()
      assertTrue("no catalog" in page)
      assertTrue("light.s0.name" in page)
    }

  @Test
  fun reloadsWhenTheManifestChanges() {
    val dir = library(serial = 1)
    var now = 0L
    val holder = LibraryHolder(dir, checkIntervalMs = 1_000, clock = { now })
    assertEquals(1, holder.current().manifest.serial)

    val next = library(serial = 2)
    File(next, DesignLibrary.MANIFEST).copyTo(File(dir, DesignLibrary.MANIFEST), overwrite = true)
    File(dir, DesignLibrary.MANIFEST).setLastModified(System.currentTimeMillis() + 10_000)
    assertEquals(1, holder.current().manifest.serial, "not before the check interval")
    now = 1_000
    assertEquals(2, holder.current().manifest.serial)

    // A broken library keeps the last good one serving.
    File(dir, DesignLibrary.MANIFEST).writeText("{")
    File(dir, DesignLibrary.MANIFEST).setLastModified(System.currentTimeMillis() + 20_000)
    now = 2_000
    assertEquals(2, holder.current().manifest.serial)
  }

  @Test
  fun refusesAnOversizedBodyBeforeReadingIt() =
    serve(library()) {
      val card = """{"card":{"type":"entities","entities":["light.kitchen"]}}"""
      // A legitimate body well over the routes' own caps still goes through.
      val ok =
        client.post("/api/schema") {
          contentType(ContentType.Application.Json)
          setBody(card + " ".repeat(1_000_000))
        }
      assertEquals(HttpStatusCode.OK, ok.status)

      // Declared too large: refused up front, by its Content-Length.
      val declared =
        client.post("/api/schema") {
          contentType(ContentType.Application.Json)
          setBody(card + " ".repeat(MAX_REQUEST_BODY_BYTES.toInt()))
        }
      assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)

      // Streamed with no length: cut off once it passes the cap.
      val streamed =
        client.post("/schema") {
          setBody(
            object : OutgoingContent.WriteChannelContent() {
              override val contentType = ContentType.Application.FormUrlEncoded

              override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully("card=".encodeToByteArray())
                val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                repeat((MAX_REQUEST_BODY_BYTES / chunk.size).toInt() + 2) {
                  channel.writeFully(chunk)
                }
              }
            }
          )
        }
      assertEquals(HttpStatusCode.PayloadTooLarge, streamed.status)
    }

  @Test
  fun tokensMatchOnlyTheSameToken() {
    assertTrue(tokensMatch("secret", "secret"))
    assertTrue(tokensMatch("sécret", "sécret"))
    assertFalse(tokensMatch(null, "secret"))
    assertFalse(tokensMatch("", "secret"))
    assertFalse(tokensMatch("secreT", "secret"))
    assertFalse(tokensMatch("secret-and-more", "secret"))
    assertFalse(tokensMatch("sécret", "secret"))
  }
}
