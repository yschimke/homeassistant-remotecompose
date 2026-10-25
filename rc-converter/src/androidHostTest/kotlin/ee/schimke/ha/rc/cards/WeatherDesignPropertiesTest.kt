package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.icons.HaIconMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The weather card's design keys keep every condition glyph apart. */
class WeatherDesignPropertiesTest {
  private val converter = WeatherForecastCardConverter()
  private val card =
    CardConfig(
      type = "weather-forecast",
      raw =
        buildJsonObject {
          put("type", "weather-forecast")
          put("entity", "weather.home")
        },
    )

  private fun snapshot(condition: String, dayCondition: String) =
    HaSnapshot(
      states =
        mapOf(
          "weather.home" to
            EntityState(
              "weather.home",
              condition,
              JsonObject(
                mapOf(
                  "temperature" to JsonPrimitive(17),
                  "forecast" to
                    buildJsonArray {
                      add(
                        buildJsonObject {
                          put("condition", dayCondition)
                          put("temperature", 18)
                          put("templow", 9)
                          put("datetime", "2026-10-17T00:00:00")
                        }
                      )
                    },
                )
              ),
            )
        )
    )

  @Test
  fun conditionsWithDifferentGlyphsHaveDifferentDesigns() {
    for ((a, b) in listOf("sunny" to "clear-night", "rainy" to "pouring", "snowy" to "hail")) {
      assertNotEquals(
        converter.designProperties(card, snapshot(a, a))?.shape,
        converter.designProperties(card, snapshot(b, b))?.shape,
        "$a vs $b",
      )
    }
  }

  @Test
  fun aTemplateDrawsTheConditionsOwnGlyph() {
    for (condition in listOf("clear-night", "pouring", "lightning-rainy", "exceptional", "odd")) {
      val properties =
        assertNotNull(converter.designProperties(card, snapshot(condition, condition)))
      val input = assertNotNull(converter.designTemplate(properties))
      val entity = input.snapshot.states.values.single()
      assertEquals(
        HaIconMap.weatherCondition(condition).name,
        HaIconMap.weatherCondition(entity.state).name,
        condition,
      )
      val day = entity.attributes.getValue("forecast").jsonArray.first().jsonObject
      assertEquals(
        HaIconMap.weatherCondition(condition).name,
        HaIconMap.weatherCondition(day["condition"]?.jsonPrimitive?.content).name,
        condition,
      )
    }
  }

  @Test
  fun showingTheCurrentWeatherKeysTheSameDesign_hidingItHasNone() {
    val snapshot = snapshot("sunny", "rainy")
    fun with(show: Boolean) =
      CardConfig(card.type, JsonObject(card.raw + ("show_current" to JsonPrimitive(show))))
    assertEquals(
      converter.designProperties(card, snapshot),
      converter.designProperties(with(true), snapshot),
    )
    kotlin.test.assertNull(converter.designProperties(with(false), snapshot))
  }

  @Test
  fun aMapsAspectRatioKeysTheSameDesign() {
    val map = MapCardConverter()
    val people = HaSnapshot(states = mapOf("person.yuri" to EntityState("person.yuri", "home")))
    fun config(vararg extra: Pair<String, String>) =
      CardConfig(
        "map",
        buildJsonObject {
          put("type", "map")
          put("entities", buildJsonArray { add(JsonPrimitive("person.yuri")) })
          for ((k, v) in extra) put(k, v)
        },
      )
    val plain = assertNotNull(map.designProperties(config(), people))
    assertEquals(plain, map.designProperties(config("aspect_ratio" to "16:9"), people))
  }
}
