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
    // The placeholder name appears once, as the `light.s0.name` binding's initial value. Content
    // descriptions read that binding too, so TalkBack never gets a baked placeholder.
    assertEquals(
      1,
      Regex(Regex.escape(CardDesignSlots.NAME_PLACEHOLDER)).findAll(document).count(),
      "placeholder name baked outside its binding",
    )
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

  @Test
  fun listTemplateTapRunsTheTappedRowsOwnAction() {
    val list =
      CardConfig(
        type = "glance",
        raw =
          buildJsonObject {
            put("type", "glance")
            put(
              "entities",
              kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("light.kitchen"))
                add(
                  buildJsonObject {
                    put("entity", "sensor.temp")
                    put("tap_action", buildJsonObject { put("action", "none") })
                  }
                )
              },
            )
          },
      )
    val playback = CardDesignTemplates.playback(list, ByteArray(0))
    val toggleS0 = ee.schimke.ha.rc.components.HaAction.Toggle("light.s0")
    assertEquals(defaultTapActionFor("light.kitchen"), playback.resolveTap(toggleS0))
    assertEquals(
      ee.schimke.ha.rc.components.HaAction.None,
      playback.resolveTap(ee.schimke.ha.rc.components.HaAction.MoreInfo("sensor.s1")),
    )
    // Anything the template encodes beyond a slot reference is ignored.
    assertEquals(
      playback.tapAction,
      playback.resolveTap(ee.schimke.ha.rc.components.HaAction.Url("https://evil.test")),
    )
    assertEquals(
      playback.tapAction,
      playback.resolveTap(ee.schimke.ha.rc.components.HaAction.Toggle("light.s9")),
    )
  }

  @Test
  fun alarmTemplateTapsAreRebuiltFromTheCardsOwnConfig() {
    val alarm =
      CardConfig(
        type = "alarm-panel",
        raw =
          buildJsonObject {
            put("type", "alarm-panel")
            put("entity", "alarm_control_panel.home")
            put(
              "states",
              kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("arm_home"))
              },
            )
          },
      )
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "alarm_control_panel.home" to
              EntityState(
                "alarm_control_panel.home",
                "disarmed",
                kotlinx.serialization.json.JsonObject(
                  mapOf("code_arm_required" to kotlinx.serialization.json.JsonPrimitive(false))
                ),
              )
          )
      )
    val playback = CardDesignTemplates.playback(alarm, ByteArray(0))
    val slot = "alarm_control_panel.s0"
    // The template's code length is ignored; the entity says no code is needed.
    assertEquals(
      ee.schimke.ha.rc.components.HaAction.AlarmIntent("alarm_control_panel.home", "arm_home", 0),
      playback.resolveTap(
        ee.schimke.ha.rc.components.HaAction.AlarmIntent(slot, "arm_home", 4),
        snapshot,
      ),
    )
    assertEquals(
      ee.schimke.ha.rc.components.HaAction.AlarmKey("alarm_control_panel.home", "7"),
      playback.resolveTap(ee.schimke.ha.rc.components.HaAction.AlarmKey(slot, "7"), snapshot),
    )
    // A service this card doesn't offer, or a key that isn't on the keypad, never runs.
    for (forged in
      listOf(
        ee.schimke.ha.rc.components.HaAction.AlarmIntent(slot, "disarm", 0),
        ee.schimke.ha.rc.components.HaAction.AlarmKey(slot, "1234"),
      )) {
      assertEquals(playback.slotTapActions[0], playback.resolveTap(forged, snapshot))
    }
    // With the keypad hidden, no key is accepted at all.
    val noKeypad =
      CardDesignTemplates.playback(
        CardConfig(
          type = "alarm-panel",
          raw =
            kotlinx.serialization.json.JsonObject(
              alarm.raw + ("show_keypad" to kotlinx.serialization.json.JsonPrimitive(false))
            ),
        ),
        ByteArray(0),
      )
    assertEquals(
      noKeypad.slotTapActions[0],
      noKeypad.resolveTap(ee.schimke.ha.rc.components.HaAction.AlarmKey(slot, "7"), snapshot),
    )
  }

  @Test
  fun templateImagesResolveOnlyToTheCardsOwnPicture() {
    val camera =
      CardConfig(
        type = "picture-entity",
        raw =
          buildJsonObject {
            put("type", "picture-entity")
            put("entity", "camera.driveway")
          },
      )
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "camera.driveway" to
              EntityState(
                "camera.driveway",
                "idle",
                kotlinx.serialization.json.JsonObject(
                  mapOf("entity_picture" to kotlinx.serialization.json.JsonPrimitive("/cam?t=1"))
                ),
              )
          )
      )
    val playback = CardDesignTemplates.playback(camera, ByteArray(0))
    assertEquals("/cam?t=1", playback.bitmapUrl(CardDesignSlots.pictureUrl("camera.s0"), snapshot))
    // Another slot, another attribute, or a URL of the template's own choosing: never fetched.
    for (url in
      listOf(
        CardDesignSlots.pictureUrl("camera.s1"),
        "rc-slot://camera.s0/token",
        "https://tracker.test/pixel.png",
      )) {
      assertEquals(null, playback.bitmapUrl(url, snapshot), url)
    }
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
