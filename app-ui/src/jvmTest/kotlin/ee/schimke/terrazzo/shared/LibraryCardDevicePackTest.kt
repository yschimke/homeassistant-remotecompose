package ee.schimke.terrazzo.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.cards.DevicePacks
import ee.schimke.ha.designs.DevicePackLibrary
import ee.schimke.ha.designs.LibraryCaptureProfile
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CommonCaptureHost
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaEntityAction
import ee.schimke.ha.rc.components.encodeHaEntityAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The device packs' designs in the shared app (desktop and the Wasm panel), played by [LibraryCard]
 * as the phone app plays them: a garage door's buttons run their own service on the card's door,
 * and a thermostat binds by the recipe its document carries, in the home's own unit.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryCardDevicePackTest {
  private val host = CommonCaptureHost(LibraryCaptureProfile.OPERATIONS)

  private fun design(id: String): DevicePacks.PackDesign =
    DevicePackLibrary.published.first { it.designId == id }

  private fun bytesOf(design: DevicePacks.PackDesign): ByteArray =
    DevicePackLibrary.capture(host, DevicePackLibrary.keys(design).first(), design).bytes

  private val garage =
    CardConfig(
      "tile",
      buildJsonObject {
        put("type", "tile")
        put("entity", "cover.my_garage")
        put("tap_action", buildJsonObject { put("action", "toggle") })
      },
    )

  private fun garageSnapshot(features: Int = 15) =
    HaSnapshot(
      states =
        mapOf(
          "cover.my_garage" to
            EntityState(
              "cover.my_garage",
              "closed",
              JsonObject(
                mapOf(
                  "friendly_name" to JsonPrimitive("Garage"),
                  "device_class" to JsonPrimitive("garage"),
                  "supported_features" to JsonPrimitive(features),
                ),
              ),
            ),
        ),
    )

  private fun entityAction(entityId: String, action: String) =
    encodeHaEntityAction(HaEntityAction(entityId, action))

  @Test
  fun aGarageDoorsOpenStopAndCloseRunTheirServiceOnTheCardsDoor() {
    val bytes = bytesOf(design("garage-door-controls-material3"))
    val snapshot = garageSnapshot()
    val playback = exactTemplatePlayback(garage, snapshot, bytes)
    for (service in listOf("open_cover", "stop_cover", "close_cover")) {
      val call =
        assertNotNull(libraryEntityAction(playback, entityAction("cover.s0", service), snapshot))
      assertEquals(HaAction.CallService("cover", service, "cover.my_garage"), call)
      // What the host's dispatch runs: that service on that door, never the card's toggle.
      assertEquals(
        CardActionCall("cover", service, "cover.my_garage"),
        cardActionCall(cardActionPayload(call)),
      )
    }
  }

  @Test
  fun anActionTheDoorsDomainDoesntListRunsNothing() {
    val bytes = bytesOf(design("garage-door-controls-material3"))
    val snapshot = garageSnapshot()
    val playback = exactTemplatePlayback(garage, snapshot, bytes)
    for (payload in
      listOf(
        entityAction("cover.s0", "unlock"),
        entityAction("cover.s0", "turn_on"),
        entityAction("cover.s1", "open_cover"),
        entityAction("lock.s0", "unlock"),
        entityAction("cover.my_garage", "open_cover"),
        // The control lock's payload while locked.
        "",
        "{not json",
        null,
      )) {
      assertNull(libraryEntityAction(playback, payload, snapshot), "$payload runs nothing")
    }
    // A door that can't stop (open and close only) runs no stop.
    val noStop = garageSnapshot(features = 3)
    assertNull(
      libraryEntityAction(
        exactTemplatePlayback(garage, noStop, bytes),
        entityAction("cover.s0", "stop_cover"),
        noStop,
      ),
    )
  }

  private val thermostat =
    CardConfig(
      "thermostat",
      buildJsonObject {
        put("type", "thermostat")
        put("entity", "climate.den")
      },
    )

  /** A thermostat in a °F home that reports no unit of its own. */
  private val fahrenheit =
    HaSnapshot(
      states =
        mapOf(
          "climate.den" to
            EntityState(
              "climate.den",
              "heat",
              JsonObject(
                mapOf(
                  "friendly_name" to JsonPrimitive("Den"),
                  "temperature" to JsonPrimitive(68),
                  "current_temperature" to JsonPrimitive(66.5),
                  "min_temp" to JsonPrimitive(45),
                  "max_temp" to JsonPrimitive(95),
                  "target_temp_step" to JsonPrimitive(1),
                  "hvac_action" to JsonPrimitive("heating"),
                ),
              ),
            ),
        ),
      temperatureUnit = "°F",
    )

  private val thermostats =
    DevicePackLibrary.published.filter { it.domain == "climate" }.map { it.designId }

  @Test
  fun aThermostatBindsItsRecipesUnitAndRangeSoItPlays() {
    assertTrue(thermostats.isNotEmpty())
    for (id in thermostats) {
      val bytes = bytesOf(design(id))
      assertNotNull(selectedDesignKey(thermostat, fahrenheit, "classic", false), id)
      val values = exactTemplateBindings(thermostat, fahrenheit, bytes)
      assertEquals(RcNamedValue.Text("°F"), values["climate.s0.unit"], id)
      assertEquals(RcNamedValue.FloatValue(45f), values["climate.s0.min_temp"], id)
      assertEquals(RcNamedValue.FloatValue(95f), values["climate.s0.max_temp"], id)
      assertEquals(RcNamedValue.FloatValue(1f), values["climate.s0.target_temp_step"], id)
      assertEquals(RcNamedValue.FloatValue(68f), values["climate.s0.target_temperature"], id)
      assertEquals(
        RcNamedValue.Text("66.5°F"),
        values["climate.s0.attributes.current_temperature_label"],
        id,
      )
      assertTrue(canBindWithCmp(bytes, values), "$id binds every value it declares")
      // Without its document the recipe isn't read, and a design that shows the unit couldn't
      // play (the overlay reads only what the thermostat card's converter pushes).
      if (id != "thermostat-controls-overlay")
        assertFalse(
          canBindWithCmp(bytes, exactTemplateBindings(thermostat, fahrenheit, ByteArray(0))),
          id,
        )
    }
  }

  @Test
  fun aSelectedThermostatDesignDrawsRatherThanFallingBack() = runComposeUiTest {
    val design = design("thermostat-controls-material3")
    val bytes = bytesOf(design)
    setContent {
      val scope = rememberCoroutineScope()
      val cache = remember { DashboardLoadCache<LibraryDesignKey, ByteArray?>(scope) { bytes } }
      MaterialTheme {
        LibraryCard(
          thermostat,
          fahrenheit,
          "classic",
          false,
          designCache = cache,
          designId = DevicePackLibrary.designId(design),
          onSource = {},
        ) {
          Text("fallback")
        }
      }
    }
    waitUntil(timeoutMillis = 10_000) {
      onAllNodesWithText("fallback").fetchSemanticsNodes().isEmpty() &&
        onAllNodesWithText("°F", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    assertTrue(
      onAllNodesWithText("Design unavailable", substring = true).fetchSemanticsNodes().isEmpty(),
    )
  }
}
