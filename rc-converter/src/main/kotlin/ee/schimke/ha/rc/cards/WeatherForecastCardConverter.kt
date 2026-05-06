package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaWeatherDay
import ee.schimke.ha.rc.components.HaWeatherForecastData
import ee.schimke.ha.rc.components.RemoteHaWeatherForecast
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `weather-forecast` card. Reads the weather entity's
 * `temperature` / `forecast` attributes from the snapshot, renders the
 * current condition + an optional daily forecast strip. The forecast
 * array isn't part of the standard state attributes — we look for it
 * under `forecast` (legacy) or `forecast.daily` (post-2024 weather
 * service); if neither is present we skip the strip.
 */
class WeatherForecastCardConverter : CardConverter {
    override val cardType: String = CardTypes.WEATHER_FORECAST

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 168

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val attrs = entity?.attributes ?: emptyMap<String, kotlinx.serialization.json.JsonElement>()
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: attrs["friendly_name"]?.jsonPrimitive?.content
            ?: entityId
            ?: "Weather"
        val condition = entity?.state ?: "unknown"
        val tempUnit = attrs["temperature_unit"]?.jsonPrimitive?.content ?: "°"
        val temp = attrs["temperature"]?.jsonPrimitive?.content
        val temperature = if (temp != null) "$temp$tempUnit" else "—"
        val showForecast = card.raw["show_forecast"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

        val forecast = (attrs["forecast"] as? JsonArray)
            ?: ((attrs["forecast"] as? JsonObject)?.get("daily") as? JsonArray)
            ?: JsonArray(emptyList())
        val days = if (showForecast) forecast.take(5).mapNotNull { day ->
            val obj = day as? JsonObject ?: return@mapNotNull null
            val cond = obj["condition"]?.jsonPrimitive?.content
            val high = obj["temperature"]?.jsonPrimitive?.content
            val low = obj["templow"]?.jsonPrimitive?.content
            val dt = obj["datetime"]?.jsonPrimitive?.content
            HaWeatherDay(
                label = (formatDayLabel(dt) ?: "").rs,
                high = (high?.let { "$it$tempUnit" } ?: "—").rs,
                low = (low?.let { "$it$tempUnit" } ?: "—").rs,
                icon = weatherIcon(cond),
            )
        } else emptyList()

        RemoteHaWeatherForecast(
            HaWeatherForecastData(
                name = name.rs,
                condition = formatCondition(condition).rs,
                temperature = temperature.rs,
                supportingLine = null,
                icon = weatherIcon(condition),
                days = days,
            ),
            modifier = modifier,
        )
    }
}

private fun weatherIcon(condition: String?): ImageVector = when (condition) {
    "sunny", "clear-night" -> Icons.Filled.WbSunny
    "partlycloudy" -> Icons.Filled.WbCloudy
    "cloudy" -> Icons.Filled.Cloud
    "fog" -> Icons.Outlined.Cloud
    "rainy", "pouring" -> Icons.Filled.Umbrella
    "lightning", "lightning-rainy" -> Icons.Filled.Thunderstorm
    "snowy", "snowy-rainy", "hail" -> Icons.Filled.AcUnit
    "windy", "windy-variant" -> Icons.Filled.Air
    else -> Icons.Filled.Cloud
}

private fun formatCondition(state: String): String =
    state.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

/** Best-effort short weekday label from an ISO-8601 datetime — we don't
 *  parse the zone, just take the date part and convert. Returns null
 *  if the input doesn't look parseable. */
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
        0 -> "Sat"; 1 -> "Sun"; 2 -> "Mon"; 3 -> "Tue"
        4 -> "Wed"; 5 -> "Thu"; 6 -> "Fri"
        else -> null
    }
}
