package ee.schimke.ha.rc

import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.client.design.DesignRepository
import ee.schimke.ha.client.design.DesignServerConfig
import ee.schimke.ha.client.design.DesignSourceKind
import ee.schimke.ha.client.design.InMemoryDesignStore
import ee.schimke.ha.client.design.RefreshResult
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.ThemeStyle
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The card design server spike end to end, with no real server: capture a values-free tile
 * template, publish it as the static files a host would serve, fetch it through the client
 * repository from a fake host, then resolve it for a live card and derive the slot bindings the
 * player receives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardDesignTemplatesEndToEndTest {

  private val card =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", "tile")
          put("entity", "light.kitchen")
          put("name", "Kitchen")
        },
    )
  private val snapshot =
    HaSnapshot(states = mapOf("light.kitchen" to EntityState("light.kitchen", "on")))

  @Test
  fun templateIsValuesFree_publishedFetchedAndBound() = runTest {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val key =
      assertNotNull(
        CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.TerrazzoHome, dark = false)
      )

    // Pipeline: capture the template and publish the host's files.
    val built = CardDesignTemplates.capture(context, key)
    val document = String(built.bytes, Charsets.ISO_8859_1)
    for (binding in listOf("light.s0.state", "light.s0.name", "light.s0.is_on")) {
      assertTrue(binding in document, "template binds $binding")
    }
    assertFalse("light.kitchen" in document, "template must not name a real entity")
    assertFalse("Kitchen" in document, "template must not bake the card's name")
    val output = DesignPublisher.publish(listOf(built), serial = 1, nowEpochMs = NOW)

    // Client: fetch from a fake static host and resolve the live card's key.
    val host =
      HttpClient(
        MockEngine { request ->
          val body =
            output.files[request.url.encodedPath.removePrefix("/designs/")]
              ?: return@MockEngine respondError(HttpStatusCode.NotFound)
          respond(body, HttpStatusCode.OK)
        }
      )
    val repository =
      DesignRepository(
        DesignServerConfig(baseUrl = "https://example.test/designs", allowUnsigned = true),
        InMemoryDesignStore(),
        host,
      ) {
        NOW
      }
    assertEquals(RefreshResult.Updated, repository.refresh())
    val blob = assertNotNull(repository.resolve(key))
    assertEquals(DesignSourceKind.Fresh, blob.source)
    assertContentEquals(built.bytes, blob.bytes)

    // Host: the values the player receives, under the template's slot names.
    val bindings = CardDesignSlots.slotBindings(card, snapshot)
    assertEquals("Kitchen", bindings.strings["light.s0.name"])
    assertEquals(true, bindings.booleans["light.s0.is_on"])
  }

  @Test
  fun libraryServesShapesTheServerDoesNot() {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val doorCard =
      CardConfig(
        type = "tile",
        raw =
          buildJsonObject {
            put("type", "tile")
            put("entity", "binary_sensor.front_door")
          },
      )
    val doorSnapshot =
      HaSnapshot(
        states =
          mapOf(
            "binary_sensor.front_door" to
              EntityState(
                "binary_sensor.front_door",
                "on",
                kotlinx.serialization.json.JsonObject(
                  mapOf("device_class" to kotlinx.serialization.json.JsonPrimitive("door"))
                ),
              )
          )
      )
    val key =
      assertNotNull(
        CardDesignTemplates.keyFor(doorCard, doorSnapshot, ThemeStyle.Material3, dark = true)
      )
    assertEquals("device_class=door;domain=binary_sensor", key.shape)
    assertFalse(key in CardDesignTemplates.spikeKeys(), "a shape the server spike doesn't publish")

    val bytes = CardDesignLibrary.template(context, key)
    val document = String(bytes, Charsets.ISO_8859_1)
    assertTrue("binary_sensor.s0.state" in document)
    assertTrue("binary_sensor.s0.name" in document)
    assertFalse("front_door" in document)
    // Memoized: the second lookup is the same document, not a fresh capture.
    assertTrue(bytes === CardDesignLibrary.template(context, key))
  }

  @Test
  fun configuredOff_resolvesNothing() = runTest {
    val repository = DesignRepository(DesignServerConfig.Off, InMemoryDesignStore(), null) { NOW }
    assertEquals(RefreshResult.Disabled, repository.refresh(force = true))
    val key =
      assertNotNull(
        CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.TerrazzoHome, dark = false)
      )
    assertEquals(null, repository.resolve(key))
  }

  @Test
  fun templateTapRunsTheCardsOwnAction() {
    val playback = CardDesignTemplates.playback(card, ByteArray(0))
    // The tile's default tap for a light, on the real entity — never anything the template encodes.
    assertEquals(defaultTapActionFor("light.kitchen"), playback.tapAction)
  }

  /**
   * With `-PcardDesignsOut=<dir>`, writes every spike design as the files a static host serves.
   * Skipped otherwise.
   */
  @Test
  fun publishSpikeDesigns() {
    val out = System.getProperty("cardDesignsOut") ?: return
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val output =
      DesignPublisher.publish(
        CardDesignTemplates.buildAll(context),
        serial = System.currentTimeMillis(),
        nowEpochMs = System.currentTimeMillis(),
      )
    for ((path, bytes) in output.files) {
      java.io.File(out, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }
  }

  private companion object {
    const val NOW = 1_700_000_000_000L
  }
}
