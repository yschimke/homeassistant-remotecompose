package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CardBindingRecipeTest {
  private fun gauge(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
    CardConfig(
      "gauge",
      buildJsonObject {
        put("type", "gauge")
        put("entity", "sensor.cpu")
        block()
      },
    )

  private fun snapshot(state: String, unit: String? = "%") =
    HaSnapshot(
      states =
        mapOf(
          "sensor.cpu" to
            EntityState(
              "sensor.cpu",
              state,
              JsonObject(
                unit?.let { mapOf("unit_of_measurement" to JsonPrimitive(it)) } ?: emptyMap()
              ),
            )
        )
    )

  @Test
  fun gaugeDefaults() {
    val bindings = CardBindingRecipes.GAUGE.evaluate(gauge(), snapshot("64"))
    assertEquals(
      mapOf(
        "card.min" to 0f,
        "card.max" to 100f,
        "card.severity_green" to 1e30f,
        "card.severity_yellow" to 1e30f,
        "card.severity_red" to 1e30f,
        "sensor.s0.numeric_state" to 64f,
      ),
      bindings.floats,
    )
    assertEquals(mapOf("card.range" to "0 – 100 %"), bindings.strings)
  }

  @Test
  fun gaugeConfigOverridesAndFallbacks() {
    val card = gauge {
      put("min", "-10")
      put("max", 42.5)
      put("unit", "°C")
      put("severity", buildJsonObject { put("yellow", 30) })
    }
    val bindings = CardBindingRecipes.GAUGE.evaluate(card, snapshot("unavailable"))
    assertEquals(-10f, bindings.floats["card.min"])
    assertEquals(42.5f, bindings.floats["card.max"])
    assertEquals(30f, bindings.floats["card.severity_yellow"])
    // A non-numeric state sits at min.
    assertEquals(-10f, bindings.floats["sensor.s0.numeric_state"])
    assertEquals("-10 – 42.5 °C", bindings.strings["card.range"])
  }

  @Test
  fun noUnitNoRangeLine() {
    val bindings = CardBindingRecipes.GAUGE.evaluate(gauge(), snapshot("1", unit = null))
    assertFalse("card.range" in bindings.strings)
  }

  @Test
  fun jsonRoundTrip() {
    val json = Json.parseToJsonElement(CardBindingRecipes.GAUGE.toJson().toString()) as JsonObject
    val parsed = assertNotNull(CardBindingRecipe.parse(json))
    assertEquals(CardBindingRecipes.GAUGE.toJson(), parsed.toJson())
    assertEquals(
      CardBindingRecipes.GAUGE.evaluate(gauge(), snapshot("12.5")),
      parsed.evaluate(gauge(), snapshot("12.5")),
    )
  }

  @Test
  fun unknownVersionIsRejected() {
    assertNull(
      CardBindingRecipe.parse(
        buildJsonObject {
          put("v", 2)
          put("prototype", "x")
        }
      )
    )
  }

  @Test
  fun malformedExpressionsAreNull() {
    val recipe =
      CardBindingRecipe.parse(
        Json.parseToJsonElement(
          """{"v":1,"prototype":"t","floats":{
              "a":{"config":{"nested":1}},"b":{"fn":"nope","args":[]},"c":{"attr":[1]},
              "${'$'}9.x":1,"d":{"first":[{"config":"missing"},7]}}}"""
        ) as JsonObject
      )!!
    assertEquals(mapOf("d" to 7f), recipe.evaluate(gauge(), snapshot("1")).floats)
  }

  @Test
  fun rangeNumbers() {
    assertEquals("0", CardBindingRecipe.formatRangeNumber(0.0))
    assertEquals("-3", CardBindingRecipe.formatRangeNumber(-3.0))
    assertEquals("0.3", CardBindingRecipe.formatRangeNumber(0.25))
    assertEquals("-0.5", CardBindingRecipe.formatRangeNumber(-0.5))
    assertEquals("-12.2", CardBindingRecipe.formatRangeNumber(-12.25))
    assertEquals("99.9", CardBindingRecipe.formatRangeNumber(99.94))
  }
}
