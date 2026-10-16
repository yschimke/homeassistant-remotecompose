package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignCatalogEntry
import ee.schimke.ha.design.DesignExample
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Picking the library design a card renders as, in a requested card style. */
class CardResolveTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()

  private fun key(theme: String) =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = theme,
    )

  private val keys =
    listOf(
      key("baked:Material3:light"),
      key("baked:Material3:dark"),
      key("style:lcd:light"),
      key("style:lcd:dark"),
      // Dark only, like Nixie.
      key("style:nixie:dark"),
    )

  private fun library(): DesignLibrary {
    val dir = Files.createTempDirectory("designs").toFile()
    val output =
      DesignPublisher.publish(
        keys.map { BuiltDesign(it, 1, template) },
        serial = 1,
        nowEpochMs = 1_000,
      )
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    val entries = keys.mapIndexed { i, key ->
      DesignCatalogEntry(
        id = DesignLibrary.idOf(key),
        sha256 = output.manifest.designs[i].sha256,
        key = key,
        example =
          DesignExample(
            "fixture:tile",
            buildJsonObject {
              put("type", "tile")
              put("entity", "light.example")
            },
            mapOf(
              "light.s0.name" to JsonPrimitive("Example"),
              "light.s0.state" to JsonPrimitive("Off"),
              "light.s0.is_on" to JsonPrimitive(false),
            ),
          ),
      )
    }
    File(dir, DesignCatalog.PATH)
      .writeText(Json.encodeToString(DesignCatalog.serializer(), DesignCatalog(1, 1_000, entries)))
    return DesignLibrary.load(dir)
  }

  private val kitchen = buildJsonObject {
    put("type", "tile")
    put("entity", "light.kitchen")
    put("name", "Kitchen")
  }

  private fun request(style: String = "classic", dark: Boolean = false) =
    ResolveRequest(
      card = kitchen,
      states = mapOf("light.kitchen" to ee.schimke.ha.model.EntityState("light.kitchen", "on")),
      cardStyle = style,
      dark = dark,
    )

  @Test
  fun aCardResolvesToTheDesignInItsStyleAndMode() {
    val library = library()
    assertEquals(
      "baked:Material3:light",
      CardResolve.resolve(library, request())!!.key.theme,
    )
    assertEquals(
      "baked:Material3:dark",
      CardResolve.resolve(library, request(dark = true))!!.key.theme,
    )
    assertEquals(
      "style:lcd:dark",
      CardResolve.resolve(library, request("lcd", dark = true))!!.key.theme,
    )
    // A style with one mode draws in it whatever mode was asked for.
    assertEquals(
      "style:nixie:dark",
      CardResolve.resolve(library, request("nixie"))!!.key.theme,
    )
    assertNull(CardResolve.resolve(library, request("braun")), "no Braun design of this card")
  }

  @Test
  fun theValuesAreTheCardsOwn() {
    val match = CardResolve.resolve(library(), request("lcd"))!!
    assertTrue(match.exact)
    assertEquals("Kitchen", match.bindings.getValue("light.s0.name").content)
    assertEquals("$API_BASE/blobs/${match.sha256}.rc", match.blob)
  }

  @Test
  fun aCardWithOtherSlotsHasNoDesign() {
    val sensor = buildJsonObject {
      put("type", "tile")
      put("entity", "sensor.temp")
    }
    assertNull(CardResolve.resolve(library(), ResolveRequest(sensor, emptyMap())))
  }

  @Test
  fun theRouteAnswersAnyOrigin() = testApplication {
    val dir = library().dir
    application { designServer(LibraryHolder(dir)) }
    val preflight = client.options("/api/cards/resolve")
    assertEquals(HttpStatusCode.NoContent, preflight.status)
    assertEquals("*", preflight.headers[HttpHeaders.AccessControlAllowOrigin])

    val body = buildJsonObject {
      put("card", kitchen)
      put("cardStyle", "lcd")
      put("dark", true)
    }
    val response =
      client.post("/api/cards/resolve") {
        header(HttpHeaders.ContentType, "application/json")
        setBody(body.toString())
      }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
    val match = Json.decodeFromString(CardMatch.serializer(), response.bodyAsText())
    assertEquals("style:lcd:dark", match.key.theme)

    assertEquals(
      HttpStatusCode.BadRequest,
      client.post("/api/cards/resolve") { setBody("not json") }.status,
    )
    val braun = buildJsonObject {
      put("card", kitchen)
      put("cardStyle", "braun")
    }
    assertEquals(
      HttpStatusCode.NotFound,
      client.post("/api/cards/resolve") { setBody(braun.toString()) }.status,
    )
  }
}
