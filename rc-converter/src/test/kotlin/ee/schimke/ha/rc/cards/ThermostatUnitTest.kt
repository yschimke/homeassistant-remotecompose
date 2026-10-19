package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The thermostat writes its temperatures in Home Assistant's unit: the entity's own
 * `temperature_unit`, else the instance's unit system (`get_config` → `unit_system.temperature`),
 * else °C. A climate entity normally carries no unit, so a °F instance must not read "°C".
 */
class ThermostatUnitTest {

  private val card =
    CardConfig(
      type = "thermostat",
      raw =
        buildJsonObject {
          put("type", "thermostat")
          put("entity", "climate.hall")
        },
    )

  private fun climate(vararg extra: Pair<String, JsonPrimitive>) =
    EntityState(
      "climate.hall",
      "heat",
      JsonObject(
        mapOf(
          "current_temperature" to JsonPrimitive(68.5),
          "temperature" to JsonPrimitive(70),
          "min_temp" to JsonPrimitive(45),
          "max_temp" to JsonPrimitive(95),
        ) + extra
      ),
    )

  private fun snapshot(entity: EntityState, unit: String?) =
    HaSnapshot(states = mapOf(entity.entityId to entity), temperatureUnit = unit)

  @Test
  fun aFahrenheitInstanceShowsFahrenheit() {
    val dial = thermostatDial(card, snapshot(climate(), "°F"))
    assertTrue(dial.centerLabel.endsWith("°F"), dial.centerLabel)
    assertTrue(dial.supportingLabel.orEmpty().endsWith("°F"), dial.supportingLabel)
  }

  @Test
  fun theEntitysOwnUnitWins() {
    val dial =
      thermostatDial(card, snapshot(climate("temperature_unit" to JsonPrimitive("°C")), "°F"))
    assertTrue(dial.centerLabel.endsWith("°C"), dial.centerLabel)
  }

  @Test
  fun celsiusWithoutAnyUnit() {
    val dial = thermostatDial(card, snapshot(climate(), null))
    assertTrue(dial.centerLabel.endsWith("°C"), dial.centerLabel)
  }

  @Test
  fun theUnitSystemIsPartOfTheReEncodeSignature() {
    val converter = ThermostatCardConverter()
    assertNotEquals(
      converter.dataSignature(card, snapshot(climate(), null)),
      converter.dataSignature(card, snapshot(climate(), "°F")),
    )
    assertEquals(
      converter.dataSignature(card, snapshot(climate(), "°F")),
      converter.dataSignature(card, snapshot(climate(), "°F")),
    )
  }
}
