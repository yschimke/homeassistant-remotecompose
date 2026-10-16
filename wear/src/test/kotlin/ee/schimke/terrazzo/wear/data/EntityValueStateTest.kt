package ee.schimke.terrazzo.wear.data

import ee.schimke.ha.rc.formatState
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wear text reads exactly what the phone's cards show: the shared `formatState`, not raw state. */
class EntityValueStateTest {

  @Test
  fun wearTextUsesTheSharedFormatter() {
    val cases =
      listOf(
        Triple("light.kitchen", EntityValue(state = "on"), "On"),
        Triple("lock.front_door", EntityValue(state = "locked"), "Locked"),
        Triple(
          "sensor.living_room",
          EntityValue(state = "21.46", unit = "°C", deviceClass = "temperature"),
          "21.5 °C",
        ),
        Triple(
          "binary_sensor.back_door",
          EntityValue(state = "on", deviceClass = "door"),
          "Open",
        ),
        Triple("sensor.x", EntityValue(state = "unknown"), "Unknown"),
      )
    for ((id, value, expected) in cases) {
      assertEquals(id, expected, value.displayState(id))
      assertEquals(id, formatState(value.toEntityState(id)), value.displayState(id))
    }
  }
}
