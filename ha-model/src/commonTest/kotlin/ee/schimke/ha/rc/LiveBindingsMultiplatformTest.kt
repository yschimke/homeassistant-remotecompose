package ee.schimke.ha.rc

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LiveBindingsMultiplatformTest {
  @Test
  fun climateTemperaturesAreFloatBindings() {
    val id = "climate.living_room"
    val attributes = buildJsonObject {
      put("current_temperature", 21.5)
      put("temperature", "22")
      put("hvac_action", "heating")
    }
    val snapshot = HaSnapshot(states = mapOf(id to EntityState(id, "heat", attributes)))
    val floats = cardSnapshotBindings(setOf(id), snapshot).floats
    assertEquals(21.5f, floats["$id.current_temperature"])
    assertEquals(22f, floats["$id.target_temperature"])
    // A missing set point (a thermostat switched off) is pushed as ABSENT, clearing the marker;
    // other domains publish no attribute floats.
    val noTarget = buildJsonObject { put("current_temperature", 19) }
    assertEquals(
      mapOf("current_temperature" to 19f, "target_temperature" to HaLiveBindings.ABSENT),
      HaLiveBindings.attributeFloats(id, noTarget),
    )
    assertEquals(emptyMap(), HaLiveBindings.attributeFloats("sensor.temp", attributes))
  }

  @Test
  fun isOnFollowsTheActiveStateConvertersBake() {
    val cases =
      mapOf(
        ("climate.living_room" to "heat") to true,
        ("climate.living_room" to "cool") to true,
        ("climate.living_room" to "heat_cool") to true,
        ("climate.living_room" to "off") to false,
        ("climate.living_room" to "unavailable") to false,
        ("cover.garage" to "open") to true,
        ("cover.garage" to "closed") to false,
        ("lock.front" to "locked") to true,
        ("media_player.tv" to "playing") to true,
        ("media_player.tv" to "paused") to false,
        ("light.kitchen" to "on") to true,
        ("switch.fan" to "off") to false,
        ("cover.garage" to "opening") to true,
        // No modelled active concept: falls back to the literal "on" or "open".
        ("sensor.temp" to "21.5") to false,
        ("scene.night" to "on") to true,
        ("valve.garden" to "open") to true,
        ("valve.garden" to "closed") to false,
      )
    cases.forEach { (entity, expected) ->
      val (id, state) = entity
      assertEquals(expected, HaLiveBindings.isOn(id, state), "$id=$state")
      val snapshot = HaSnapshot(states = mapOf(id to EntityState(id, state)))
      assertEquals(
        expected,
        cardSnapshotBindings(setOf(id), snapshot).booleans["$id.is_on"],
        "$id=$state",
      )
    }
  }

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
        "NaN" to "NaN",
        "Infinity" to "Infinity",
        "-Infinity" to "-Infinity",
        "1e-99999999999" to "1e-99999999999",
        "1e-2147483648" to "0",
        "1e99999999999" to "1e99999999999",
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
