@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.RemoteHaWeatherForecast
import ee.schimke.ha.rc.components.RemoteHaWeatherForecastNarrow
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

  /**
   * `ha-cards/weather-forecast-card-wide`: the current row alone, for a short launcher cell. A
   * launcher widget knows its size at capture and goes further: the icon over the temperature in
   * one column, the condition over the temperature in two, and from three columns and two rows the
   * current conditions over as many forecast days as its width holds, so a big widget shows the
   * forecast rather than one row in a blank cell.
   */
  @Composable
  fun Wide(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    val data = weatherData(card, snapshot)
    val cell = LocalWidgetCaptureSizeDp.current
    when {
      cell == null -> RemoteHaWeatherForecastWide(data, modifier)
      cell.widthDp < WEATHER_ROW_MIN_WIDTH_DP -> RemoteHaWeatherForecastNarrow(data, modifier)
      cell.widthDp < WEATHER_FORECAST_MIN_WIDTH_DP -> RemoteHaWeatherForecastWear(data, modifier)
      cell.heightDp >= WEATHER_FORECAST_MIN_HEIGHT_DP && data.days.isNotEmpty() ->
        RemoteHaWeatherForecast(
          data.copy(days = data.days.take((cell.widthDp - 28) / WEATHER_DAY_MIN_DP)),
          modifier,
          fillHeight = true,
          showExtras = false,
          showCondition = cell.widthDp >= WEATHER_CONDITION_MIN_WIDTH_DP,
        )
      else -> RemoteHaWeatherForecastWide(data, modifier)
    }
  }

  // One launcher column is too narrow for a row; three hold a forecast day per 48 dp; two rows
  // hold the current row over the strip.
  private const val WEATHER_ROW_MIN_WIDTH_DP = 100
  private const val WEATHER_FORECAST_MIN_WIDTH_DP = 180
  private const val WEATHER_FORECAST_MIN_HEIGHT_DP = 150
  private const val WEATHER_DAY_MIN_DP = 48

  /** Below this width the forecast's current row leaves the condition to its icon. */
  private const val WEATHER_CONDITION_MIN_WIDTH_DP = 260

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
