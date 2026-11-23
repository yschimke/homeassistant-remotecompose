package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [CardBindingRecipes.CLIMATE]: a thermostat design reads its entity's own unit, range and step,
 * and its readings as labels, so a °F thermostat reads °F on its own range.
 */
class ClimateRecipeTest {
  private val card =
    CardConfig(
      "thermostat",
      buildJsonObject {
        put("type", "thermostat")
        put("entity", "climate.hall")
      },
    )

  private fun snapshot(
    state: String = "heat",
    unitSystem: String? = null,
    vararg attributes: Pair<String, Any>,
  ) =
    HaSnapshot(
      states =
        mapOf(
          "climate.hall" to
            EntityState(
              "climate.hall",
              state,
              JsonObject(
                attributes.associate { (k, v) ->
                  k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())
                },
              ),
            ),
        ),
      temperatureUnit = unitSystem,
    )

  @Test
  fun aFahrenheitThermostatReadsItsOwnUnitAndRange() {
    val bindings =
      CardBindingRecipes.CLIMATE.evaluate(
        card,
        snapshot(
          "heat",
          "°F",
          "current_temperature" to 68.5,
          "temperature" to 70,
          "min_temp" to 45,
          "max_temp" to 95,
          "target_temp_step" to 1,
          "hvac_action" to "heating",
        ),
      )
    assertEquals(
      mapOf(
        "climate.s0.current_temperature" to 68.5f,
        "climate.s0.target_temperature" to 70f,
        "climate.s0.min_temp" to 45f,
        "climate.s0.max_temp" to 95f,
        "climate.s0.target_temp_step" to 1f,
      ),
      bindings.floats,
    )
    assertEquals(
      mapOf(
        "climate.s0.unit" to "°F",
        "climate.s0.attributes.current_temperature_label" to "68.5°F",
        "climate.s0.attributes.temperature_label" to "↑ 70°F",
        "climate.s0.attributes.hvac_action_label" to "Heating",
      ),
      bindings.strings,
    )
  }

  @Test
  fun theEntitysOwnUnitWinsAndDefaultsFillTheRest() {
    val own =
      CardBindingRecipes.CLIMATE.evaluate(
        card,
        snapshot("heat_cool", "°F", "temperature_unit" to "°C"),
      )
    assertEquals("°C", own.strings["climate.s0.unit"])
    assertEquals(7f, own.floats["climate.s0.min_temp"])
    assertEquals(35f, own.floats["climate.s0.max_temp"])
    assertEquals(0.5f, own.floats["climate.s0.target_temp_step"])
    // A thermostat holding a range reports no set point, and none is shown.
    assertEquals(-9999f, own.floats["climate.s0.target_temperature"])
    assertEquals("—", own.strings["climate.s0.attributes.temperature_label"])
    assertEquals("—", own.strings["climate.s0.attributes.current_temperature_label"])
    assertEquals("Heat cool", own.strings["climate.s0.attributes.hvac_action_label"])
    // A host that hasn't read Home Assistant's unit yet: °C, as the thermostat card shows.
    assertEquals(
      "°C",
      CardBindingRecipes.CLIMATE.evaluate(card, snapshot()).strings["climate.s0.unit"],
    )
  }

  @Test
  fun aHostThatDoesntKnowTheNewerExpressionsShowsNeutralValues() {
    // As an older host reads the recipe: `host` and the label functions evaluate to null there.
    val older =
      CardBindingRecipe.parse(
        kotlinx.serialization.json.Json.parseToJsonElement(
          CardBindingRecipes.CLIMATE.toJson()
            .toString()
            .replace("\"host\"", "\"unknown_expression\"")
            .replace("\"with_unit\"", "\"unknown_fn\"")
            .replace("\"hvac_label\"", "\"unknown_fn\""),
        ) as JsonObject,
      )!!
    val bindings = older.evaluate(card, snapshot("heat", "°F", "current_temperature" to 68.5))
    assertEquals("°", bindings.strings["climate.s0.unit"])
    assertEquals("—", bindings.strings["climate.s0.attributes.current_temperature_label"])
    assertEquals("", bindings.strings["climate.s0.attributes.hvac_action_label"])
    assertEquals(68.5f, bindings.floats["climate.s0.current_temperature"])
  }

  @Test
  fun labelFunctions() {
    val with = CardBindingRecipe.FUNCTIONS.getValue("with_unit")
    assertEquals("19.5°C", with(listOf(19.5, "°C")))
    assertEquals("21°F", with(listOf(21.0, "°F")))
    assertEquals(null, with(listOf(null, "°C")))
    val concat = CardBindingRecipe.FUNCTIONS.getValue("concat")
    assertEquals("↑ 21", concat(listOf("↑ ", 21.0)))
    assertEquals(null, concat(listOf("↑ ", null)))
    assertEquals("Idle", CardBindingRecipe.hvacLabel("idle", "heat"))
    assertEquals("Fan only", CardBindingRecipe.hvacLabel(null, "fan_only"))
    assertEquals(null, CardBindingRecipe.hvacLabel(null, ""))
  }
}
