package ee.schimke.ha.addon.routes

import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class StreamRouteTest {
  @Test
  fun stateBindingsKeepUnitsOnLiveUpdates() {
    val before =
      EntityState(
        "sensor.temp",
        "21.500",
        buildJsonObject { put("unit_of_measurement", "°C") },
      )
    val after = before.copy(state = "23.125")

    val initial = buildJsonObject { putStateBindings(before) }
    val update = buildJsonObject { putStateBindings(after) }

    assertEquals("21.5 °C", initial["sensor.temp.state"]?.jsonPrimitive?.content)
    assertEquals("23.13 °C", update["sensor.temp.state"]?.jsonPrimitive?.content)
    assertEquals("23.125", update["sensor.temp.numeric_state"]?.jsonPrimitive?.content)
  }
}
