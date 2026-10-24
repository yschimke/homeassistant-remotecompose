package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ContainerTintTest {
  private fun tint(id: String, state: String, vararg attrs: Pair<String, String>): Int? =
    ContainerTint.argbFor(
      CardConfig(
        "tile",
        JsonObject(mapOf("type" to JsonPrimitive("tile"), "entity" to JsonPrimitive(id))),
      ),
      HaSnapshot(
        states =
          mapOf(
            id to
              EntityState(
                id,
                state,
                JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }),
              )
          )
      ),
    )

  @Test
  fun aStateThatMeansSomethingTintsTheContainer() {
    val heating = assertNotNull(tint("climate.lounge", "heat", "hvac_action" to "heating"))
    val cooling = assertNotNull(tint("climate.lounge", "cool", "hvac_action" to "cooling"))
    assertNotEquals(heating, cooling)
    assertNotNull(tint("light.kitchen", "on"))
    assertNotNull(tint("alarm_control_panel.house", "triggered"))
    assertNotNull(tint("binary_sensor.leak", "on", "device_class" to "moisture"))
  }

  @Test
  fun anIdleOrPlainStateKeepsTheDefault() {
    assertNull(tint("climate.lounge", "heat", "hvac_action" to "idle"))
    assertNull(tint("light.kitchen", "off"))
    assertNull(tint("alarm_control_panel.house", "disarmed"))
    assertNull(tint("binary_sensor.door", "on", "device_class" to "door"))
    assertNull(tint("sensor.temperature", "21"))
  }

  @Test
  fun aTintIsTheDarkSurfaceWithAQuarterOfTheAccent() {
    val heating = assertNotNull(tint("climate.lounge", "heat", "hvac_action" to "heating"))
    // Dark enough for light text: every channel well under the midpoint.
    for (shift in listOf(16, 8, 0)) assert((heating ushr shift and 0xff) < 0x80)
    assertEquals(0xff, heating ushr 24)
  }
}
