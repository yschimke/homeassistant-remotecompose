package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.components.ThemeStyle
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Designs by key: the library's, or captured on request when the library has none. */
class DesignGeneratorTest {
  private val kitchen = buildJsonObject {
    put("type", "tile")
    put("entity", "light.kitchen")
    put("name", "Kitchen")
  }
  private val states = mapOf("light.kitchen" to EntityState("light.kitchen", "on"))

  /** The key the app gives the kitchen tile, as the app computes it. */
  private val kitchenKey: DesignKey =
    requireNotNull(
      CardDesignTemplates.keyFor(
        CardConfig("tile", kitchen),
        HaSnapshot(states = states),
        ThemeStyle.entries.first(),
        dark = false,
      )
    )

  /** A library holding one design, for another key than the kitchen tile's. */
  private fun libraryDir(): File {
    val dir = Files.createTempDirectory("designs").toFile()
    val held = kitchenKey.copy(theme = DesignKey.bakedTheme(ThemeStyle.entries.first().name, true))
    val output =
      DesignPublisher.publish(
        listOf(BuiltDesign(held, 1, javaClass.getResource("/tile-light.rc")!!.readBytes())),
        serial = 1,
        nowEpochMs = 1_000,
      )
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    return dir
  }

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun aKeyTheLibraryLacksIsCapturedAndServed() = testApplication {
    application { designServer(LibraryHolder(libraryDir())) }
    val response =
      client.post("$API_BASE/generate") {
        setBody(json.encodeToString(DesignKey.serializer(), kitchenKey))
      }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    val design = json.decodeFromString(GeneratedDesign.serializer(), response.bodyAsText())
    assertTrue(design.generated)
    assertEquals(kitchenKey, design.key)

    val blob = client.get(design.blob)
    assertEquals(HttpStatusCode.OK, blob.status)
    val bytes = blob.bodyAsBytes()
    assertEquals(design.sha256, Sha256.hex(bytes))
    // A document the player reads, binding the tile's slot.
    val names = RcDocumentCodec.decode(bytes).operations.toString()
    assertTrue("light.s0" in names, "the template binds the tile's slot")

    // Asked again, the same design: captured once.
    val again =
      client.post("$API_BASE/generate") {
        setBody(json.encodeToString(DesignKey.serializer(), kitchenKey))
      }
    assertEquals(design, json.decodeFromString(GeneratedDesign.serializer(), again.bodyAsText()))
  }

  @Test
  fun aBlobTwoKeysShareOutlivesEvictingOne(): Unit = runBlocking {
    // Keys that differ only in a variant the template doesn't draw capture the same bytes; with
    // room for one key, evicting the first must leave the blob the second still serves.
    val generator = DesignGenerator(LibraryHolder(libraryDir()), maxGenerated = 1)
    val first = generator.design(kitchenKey)
    val second = generator.design(kitchenKey.copy(variant = "undrawn"))
    assertEquals(first.sha256, second.sha256)
    assertNotNull(generator.blob(second.sha256))
  }

  @Test
  fun aKeyTheLibraryHoldsIsTheLibrarys() = testApplication {
    application { designServer(LibraryHolder(libraryDir())) }
    val held = kitchenKey.copy(theme = DesignKey.bakedTheme(ThemeStyle.entries.first().name, true))
    val response =
      client.post("$API_BASE/generate") {
        setBody(json.encodeToString(DesignKey.serializer(), held))
      }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    assertFalse(
      json.decodeFromString(GeneratedDesign.serializer(), response.bodyAsText()).generated
    )
  }

  @Test
  fun aKeyThatCantBeCapturedHereIsNotFound() = testApplication {
    application { designServer(LibraryHolder(libraryDir())) }
    for (key in
      listOf(
        kitchenKey.copy(cardType = "no-such-card"),
        kitchenKey.copy(shape = "no=such;shape=at-all"),
        kitchenKey.copy(surface = DesignSurface.Launcher),
        kitchenKey.copy(rcApiLevel = kitchenKey.rcApiLevel + 1),
      )) {
      val response =
        client.post("$API_BASE/generate") {
          setBody(json.encodeToString(DesignKey.serializer(), key))
        }
      assertEquals(HttpStatusCode.NotFound, response.status, "$key: ${response.bodyAsText()}")
    }
    assertEquals(
      HttpStatusCode.BadRequest,
      client.post("$API_BASE/generate") { setBody("not a key") }.status,
    )
  }

  @Test
  fun aCardWithItsStatesResolvesExactlyWithTheConvertersValues() = testApplication {
    application { designServer(LibraryHolder(libraryDir())) }
    val body = buildJsonObject {
      put("card", kitchen)
      put(
        "states",
        buildJsonObject { put("light.kitchen", buildJsonObject { put("state", "on") }) },
      )
    }
    val response = client.post("/api/cards/resolve") { setBody(body.toString()) }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    val match = json.decodeFromString(CardMatch.serializer(), response.bodyAsText())
    assertTrue(match.exact)
    assertEquals(kitchenKey, match.key)
    assertEquals(JsonPrimitive("Kitchen"), match.bindings["light.s0.name"], "${match.bindings}")
    assertNotNull(client.get(match.blob).takeIf { it.status == HttpStatusCode.OK })
  }
}
