package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignCatalogEntry
import ee.schimke.ha.design.DesignExample
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
  fun anUnavailableValueIsNeverTheExamples() {
    val pushed =
      CardResolve.pushedValues(
        names = listOf("sensor.s0.numeric_state", "sensor.s0.state", "card.range"),
        // The card has a numeric_state with no JSON form (NaN), and no card.range.
        own =
          listOf(
            BindingSchema("sensor.s0.numeric_state", BindingType.Float, null),
            BindingSchema("sensor.s0.state", BindingType.Text, JsonPrimitive("unavailable")),
          ),
        example =
          mapOf(
            "sensor.s0.numeric_state" to JsonPrimitive(42.0),
            "card.range" to JsonPrimitive("0 – 100"),
          ),
      )
    assertEquals(JsonNull, pushed["sensor.s0.numeric_state"], "never the example's 42")
    assertEquals("unavailable", pushed.getValue("sensor.s0.state").content)
    assertEquals("0 – 100", pushed.getValue("card.range").content, "converter-only: the example's")
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

  @Test
  fun aSmallPlayerGetsTheDesignMadeForIt() = testApplication {
    val dir = library().dir
    application { designServer(LibraryHolder(dir)) }
    fun resolve(player: Boolean): CardMatch {
      val body = buildJsonObject {
        put("card", kitchen)
        if (player) {
          put(
            "player",
            buildJsonObject {
              put("density", 1)
              put("fontScale", 1)
            },
          )
        }
      }
      return runBlocking {
        val response =
          client.post("/api/cards/resolve") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body.toString())
          }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        Json.decodeFromString(CardMatch.serializer(), response.bodyAsText())
      }
    }
    val full = resolve(player = false)
    val small = resolve(player = true)
    // Served where the match says, with the same values, and playable on the panel's player. This
    // tile reads nothing of the display, so made for the panel it may be the same document.
    assertTrue(small.notes.none { it.startsWith("Not playable") }, small.notes.toString())
    assertEquals("$API_BASE/blobs/${small.sha256}.rc", small.blob)
    assertEquals(full.bindings, small.bindings)
    val blob = client.get("/designs/v1/blobs/${small.sha256}.rc")
    assertEquals(HttpStatusCode.OK, blob.status)
    val bytes = blob.readRawBytes()
    val folded = RcDocumentCodec.decode(bytes, RcWireLimits())
    // Nothing reads the player's density or font size any more.
    assertTrue(
      folded.operations.none { it is RcFloatExpression && it.readsDisplay() },
      "${folded.operations}",
    )
    System.getenv("PLAYER_FOLDING_TILE_OUT")?.let { File(it).writeBytes(bytes) }
  }

  @Test
  fun aPlayerThatKnowsItsWidthGetsAGridLaidOutForIt() = testApplication {
    application { designServer(LibraryHolder(library().dir)) }
    val lights = listOf("light.kitchen", "light.hall", "light.porch")
    fun resolve(width: Int?): ByteArray {
      val body = buildJsonObject {
        put(
          "card",
          buildJsonObject {
            put("type", "grid")
            put("columns", 3)
            put("square", false)
            put(
              "cards",
              kotlinx.serialization.json.JsonArray(
                lights.map { id ->
                  buildJsonObject {
                    put("type", "tile")
                    put("entity", id)
                  }
                },
              ),
            )
          },
        )
        put("states", buildJsonObject { lights.forEach { put(it, "on") } })
        put(
          "player",
          buildJsonObject {
            put("density", 1)
            put("fontScale", 1)
            if (width != null) {
              put("width", width)
              put("height", 432)
            }
          },
        )
      }
      return runBlocking {
        val response =
          client.post("/api/cards/resolve") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body.toString())
          }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val match = Json.decodeFromString(CardMatch.serializer(), response.bodyAsText())
        client.get("/designs/v1/blobs/${match.sha256}.rc").readRawBytes()
      }
    }
    fun stateLayouts(bytes: ByteArray) =
      Regex("StateLayout")
        .findAll(RcDocumentCodec.decode(bytes, RcWireLimits()).operations.toString())
        .count()
    // At any width the grid chooses its columns as it's played, by a state layout over its width;
    // made for a 776 dp screen it has chosen them already, and carries one layout.
    assertTrue(stateLayouts(resolve(width = null)) > 0, "a grid for any width has a size gate")
    assertEquals(0, stateLayouts(resolve(width = 776)), "a grid for a known width has none")
  }

  /** Whether this expression reads the density (27) or font size (33) system variable. */
  private fun RcFloatExpression.readsDisplay(): Boolean = expression.any {
    it.referencedId == DENSITY || it.referencedId == FONT_SIZE
  }

  private companion object {
    /** AndroidX's system variable ids: the display's density and default text size. */
    const val DENSITY = 27
    const val FONT_SIZE = 33
  }
}
