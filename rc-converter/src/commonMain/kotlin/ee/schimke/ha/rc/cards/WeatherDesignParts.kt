@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.RemoteHaWeatherForecast
import ee.schimke.ha.rc.components.RemoteHaWeatherForecastWear
import ee.schimke.ha.rc.components.RemoteHaWeatherForecastWide
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts the weather forecast card's design arranges (card-designs/ui-builder/designs/
 * `weather-forecast.uid`): each draws one form of the card being drawn ([LocalCardInput]); which
 * form a surface gets is the design's `card-size-mode` and `size-tiers`. The card design renderer
 * draws them through [components]; ha-cards-catalog's `HaWeatherForecastCard*` wrappers put the
 * same calls on UI Builder's shelf.
 */
object WeatherDesignParts {
  /** The card being drawn, or a forecast for `weather.home` in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          "weather-forecast",
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("weather-forecast"),
              "entity" to JsonPrimitive("weather.home"),
            )
          ),
        ),
        HaSnapshot(),
      )

  /**
   * `ha-cards/weather-forecast-card-full`: the current conditions over the forecast strip; [fill]
   * fills a launcher cell's height and leaves the conditions chips to the app card.
   */
  @Composable
  fun Full(fill: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input()
    val data = weatherData(card, snapshot)
    if (fill) RemoteHaWeatherForecast(data, modifier, fillHeight = true, showExtras = false)
    else RemoteHaWeatherForecast(data, modifier)
  }

  /** `ha-cards/weather-forecast-card-wide`: the current row alone, for a short launcher cell. */
  @Composable
  fun Wide(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    RemoteHaWeatherForecastWide(weatherData(card, snapshot), modifier)
  }

  /** `ha-cards/weather-forecast-card-wear`: the Wear form; [large] for the Large container. */
  @Composable
  fun Wear(large: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input()
    RemoteHaWeatherForecastWear(weatherData(card, snapshot), modifier, large = large)
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/weather-forecast-card-full" to
        { node, modifier ->
          Full(node.bool("fill"), modifier)
        },
      "ha-cards/weather-forecast-card-wide" to { _, modifier -> Wide(modifier) },
      "ha-cards/weather-forecast-card-wear" to
        { node, modifier ->
          Wear(node.bool("large"), modifier)
        },
    )
}
