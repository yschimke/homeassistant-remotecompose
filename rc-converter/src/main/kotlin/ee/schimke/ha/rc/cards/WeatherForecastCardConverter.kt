@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaWeatherDay
import ee.schimke.ha.rc.components.HaWeatherExtra
import ee.schimke.ha.rc.components.HaWeatherForecastData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaWeatherForecast
import ee.schimke.ha.rc.components.RemoteHaWeatherForecastWide
import ee.schimke.ha.rc.formatValueWithUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `weather-forecast` card. Reads the weather entity's `temperature` / `forecast` attributes from
 * the snapshot, renders the current condition + an optional daily forecast strip. The forecast
 * array isn't part of the standard state attributes — we look for it under `forecast` (legacy) or
 * `forecast.daily` (post-2024 weather service); if neither is present we skip the strip.
 */
class WeatherForecastCardConverter : CardConverter {
  override val cardType: String = CardTypes.WEATHER_FORECAST

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  // The high/low line under the headline temperature adds a row to the
  // current-conditions block. The default pin stays "medium" (current row
  // + forecast, no conditions chips) — those only surface once the user
  // grows the widget past WeatherExtrasMinHeightDp.
  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 178

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val attrs = entity?.attributes ?: emptyMap<String, kotlinx.serialization.json.JsonElement>()
    val name =
      card.raw["name"]?.jsonPrimitive?.content
        ?: attrs["friendly_name"]?.jsonPrimitive?.content
        ?: entityId
        ?: "Weather"
    val condition = entity?.state ?: "unknown"
    val tempUnit = attrs["temperature_unit"]?.jsonPrimitive?.content ?: "°"
    val temp = attrs["temperature"]?.jsonPrimitive?.content
    val temperature = temp?.let { formatValueWithUnit(it, tempUnit) } ?: "—"
    val showForecast =
      card.raw["show_forecast"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

    val forecast =
      (attrs["forecast"] as? JsonArray)
        ?: ((attrs["forecast"] as? JsonObject)?.get("daily") as? JsonArray)
        ?: JsonArray(emptyList())
    val days =
      if (showForecast)
        forecast.take(5).mapNotNull { day ->
          val obj = day as? JsonObject ?: return@mapNotNull null
          val cond = obj["condition"]?.jsonPrimitive?.content
          val high = obj["temperature"]?.jsonPrimitive?.content
          val low = obj["templow"]?.jsonPrimitive?.content
          val dt = obj["datetime"]?.jsonPrimitive?.content
          HaWeatherDay(
            label = formatDayLabel(dt) ?: "",
            high = high?.let { formatValueWithUnit(it, tempUnit) } ?: "—",
            low = low?.let { formatValueWithUnit(it, tempUnit) } ?: "—",
            icon = weatherIcon(cond),
          )
        }
      else emptyList()

    // Today's high / low — the first forecast day is "today"; surface it
    // under the headline temperature to match HA's tile-styled weather card.
    val highLow = days.firstOrNull()?.let { "${it.high} / ${it.low}" }

    val data =
      HaWeatherForecastData(
        entityId = entityId,
        name = name,
        condition = LiveValues.state(entityId, formatCondition(condition)),
        temperature = temperature,
        supportingLine = null,
        highLow = highLow,
        icon = weatherIcon(condition),
        days = days,
        extras = weatherExtras(attrs),
      )

    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> RemoteHaWeatherForecast(data, modifier)
      CardSizeMode.Fixed ->
        // Single width ladder — alpha010 collapses nested / multi-rung /
        // cross-axis breakpoints to tier 0 (#224), so we get exactly two
        // reliable tiers off the one dimension launcher cells actually pin
        // (width; height wraps — see RemoteSizeBreakpoint's docs):
        //   tier 0 (< 300 dp, e.g. 4×1) → current row only (Wide)
        //   tier 1 (≥ 300 dp, 5×2 / 6×3) → current row + forecast strip,
        //     which grows to fill a taller cell.
        //
        // The conditions chips are deliberately *not* in either widget tier:
        // a mid 5×2 cell can't fit current + forecast + chips without the
        // forecast temps dropping out, and one width threshold can't tell a
        // 5×2 from a roomy 6×3 to gate them. They live in the full app card
        // (Wrap mode) instead — Principle 4, "compact ≠ stripped": the
        // widget keeps the forecast rather than a half-rendered everything.
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(WeatherFullMinDp),
          modifier = modifier.fillMaxSize(),
        ) { tier ->
          when (tier) {
            0 -> RemoteHaWeatherForecastWide(data, RemoteModifier.fillMaxSize())
            else ->
              RemoteHaWeatherForecast(
                data,
                RemoteModifier.fillMaxSize(),
                fillHeight = true,
                showExtras = false,
              )
          }
        }
    }
  }
}

// Below this width the full card's forecast strip can't sit under the
// current row without cramping (the short `4×1` launcher cell, ~288 dp
// wide but only ~84 dp tall, is the motivating case — #355), so we drop
// to the Wide current-row-only variant. Above it the full card renders
// and `fillHeight` lets it claim a taller cell instead of leaving the
// bottom half blank.
private const val WeatherFullMinDp = 300

/**
 * Supplementary current-conditions read-outs pulled from the weather entity's attributes — the same
 * "feels like / humidity / wind / pressure" data HA shows under its weather card. Each entry is
 * emitted only when its attribute is present; the row is capped so it stays a single tidy line on
 * the widest widget.
 */
private fun weatherExtras(
  attrs: Map<String, kotlinx.serialization.json.JsonElement>
): List<HaWeatherExtra> {
  fun attr(key: String): String? = attrs[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
  val extras = mutableListOf<HaWeatherExtra>()
  attr("apparent_temperature")?.let {
    val unit = attr("temperature_unit") ?: "°"
    extras += HaWeatherExtra(Icons.Filled.Thermostat, "Feels", formatValueWithUnit(it, unit))
  }
  attr("humidity")?.let {
    extras += HaWeatherExtra(Icons.Filled.WaterDrop, "Humidity", formatValueWithUnit(it, "%"))
  }
  attr("wind_speed")?.let {
    val unit = attr("wind_speed_unit") ?: ""
    extras += HaWeatherExtra(Icons.Filled.Air, "Wind", formatValueWithUnit(it, unit))
  }
  attr("pressure")?.let {
    val unit = attr("pressure_unit") ?: ""
    extras += HaWeatherExtra(Icons.Filled.Compress, "Pressure", formatValueWithUnit(it, unit))
  }
  attr("uv_index")?.let {
    extras += HaWeatherExtra(Icons.Filled.WbTwilight, "UV", formatValueWithUnit(it, ""))
  }
  attr("cloud_coverage")?.let {
    extras += HaWeatherExtra(Icons.Filled.Cloud, "Cloud", formatValueWithUnit(it, "%"))
  }
  // Cap to keep the chips on one row even on the widest widget; the first
  // four (feels / humidity / wind / pressure) are the most useful glance.
  return extras.take(4)
}

private fun weatherIcon(condition: String?): ImageVector =
  when (condition) {
    "sunny",
    "clear-night" -> Icons.Filled.WbSunny
    "partlycloudy" -> Icons.Filled.WbCloudy
    "cloudy" -> Icons.Filled.Cloud
    "fog" -> Icons.Outlined.Cloud
    "rainy",
    "pouring" -> Icons.Filled.Umbrella
    "lightning",
    "lightning-rainy" -> Icons.Filled.Thunderstorm
    "snowy",
    "snowy-rainy",
    "hail" -> Icons.Filled.AcUnit
    "windy",
    "windy-variant" -> Icons.Filled.Air
    else -> Icons.Filled.Cloud
  }

private fun formatCondition(state: String): String =
  state.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

/**
 * Best-effort short weekday label from an ISO-8601 datetime — we don't parse the zone, just take
 * the date part and convert. Returns null if the input doesn't look parseable.
 */
private fun formatDayLabel(datetime: String?): String? {
  if (datetime.isNullOrEmpty()) return null
  val datePart = datetime.substringBefore('T')
  val parts = datePart.split('-')
  if (parts.size != 3) return null
  val (y, m, d) = parts
  val year = y.toIntOrNull() ?: return null
  val month = m.toIntOrNull() ?: return null
  val day = d.toIntOrNull() ?: return null
  // Zeller's-ish — day-of-week from the proleptic Gregorian calendar.
  val q = day
  val mAdj = if (month < 3) month + 12 else month
  val yAdj = if (month < 3) year - 1 else year
  val k = yAdj % 100
  val j = yAdj / 100
  val h = (q + 13 * (mAdj + 1) / 5 + k + k / 4 + j / 4 + 5 * j) % 7
  return when (h) {
    0 -> "Sat"
    1 -> "Sun"
    2 -> "Mon"
    3 -> "Tue"
    4 -> "Wed"
    5 -> "Thu"
    6 -> "Fri"
    else -> null
  }
}
