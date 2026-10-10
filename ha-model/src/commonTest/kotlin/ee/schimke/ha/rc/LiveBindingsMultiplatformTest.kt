package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LiveBindingsMultiplatformTest {
  @Test
  fun decimalRoundingMatchesAuthoringOnEveryHost() {
    val cases =
      mapOf(
        "1.005" to "1.01",
        "-1.005" to "-1.01",
        "9.999" to "10",
        "1e-3" to "0",
        "-0.001" to "0",
        "1e3" to "1000",
        "21.500" to "21.5",
        "21.xx" to "21",
      )
    cases.forEach { (input, expected) ->
      assertEquals(expected, formatValueWithUnit(input, null), input)
    }
  }

  @Test
  fun changedSnapshotsRefreshFormattedAndNumericBindings() {
    fun snapshot(value: String) =
      HaSnapshot(
        states =
          mapOf(
            "sensor.temp" to
              EntityState(
                "sensor.temp",
                value,
                buildJsonObject { put("unit_of_measurement", "°C") },
              )
          )
      )
    val before = cardSnapshotBindings(setOf("sensor.temp"), snapshot("21.500"))
    val after = cardSnapshotBindings(setOf("sensor.temp"), snapshot("23.125"))
    assertEquals("21.5 °C", before.strings["sensor.temp.state"])
    assertEquals("23.13 °C", after.strings["sensor.temp.state"])
    assertEquals(23.125f, after.floats["sensor.temp.numeric_state"])
  }
}
