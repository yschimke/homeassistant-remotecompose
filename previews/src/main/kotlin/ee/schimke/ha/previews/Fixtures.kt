package ee.schimke.ha.previews

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun card(yamlLike: String): CardConfig {
    val obj = json.parseToJsonElement(yamlLike).jsonObject
    val type = obj["type"]!!.toString().trim('"')
    return CardConfig(type = type, raw = obj)
}

fun state(
    entityId: String,
    state: String,
    attributes: Map<String, String> = emptyMap(),
): Pair<String, EntityState> {
    val attrJson = JsonObject(attributes.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
    return entityId to EntityState(entityId = entityId, state = state, attributes = attrJson)
}

fun snapshot(vararg states: Pair<String, EntityState>): HaSnapshot =
    HaSnapshot(states = states.toMap())

/** Build a `weather.*` entity carrying a `forecast` JSON array — needed for
 *  weather-forecast previews because the basic [state] helper only accepts
 *  `Map<String, String>` attributes. */
private fun weatherStateWithForecast(
    entityId: String,
    condition: String,
    attrs: Map<String, String>,
    forecast: List<JsonObject>,
): Pair<String, EntityState> {
    val merged: Map<String, JsonElement> =
        attrs.mapValues { JsonPrimitive(it.value) } + ("forecast" to JsonArray(forecast))
    return entityId to EntityState(
        entityId = entityId,
        state = condition,
        attributes = JsonObject(merged),
    )
}

private fun forecastDay(
    datetime: String,
    condition: String,
    high: Double,
    low: Double,
): JsonObject = JsonObject(
    mapOf(
        "datetime" to JsonPrimitive(datetime),
        "condition" to JsonPrimitive(condition),
        "temperature" to JsonPrimitive(high),
        "templow" to JsonPrimitive(low),
    ),
)

/** Fixture snapshots used across previews. Mirrors HA's ui-lovelace.yaml
 * so RC renderings can be diffed apples-to-apples against the committed
 * reference captures. */
object Fixtures {
    val livingRoomTemp = snapshot(
        state("sensor.living_room", "21.4",
            mapOf(
                "friendly_name" to "Living Room",
                "unit_of_measurement" to "°C",
                "device_class" to "temperature",
            )),
    )

    val kitchenLight = snapshot(
        state("light.kitchen", "on",
            mapOf("friendly_name" to "Kitchen", "brightness" to "200")),
    )

    /** Combined snapshot matching `integration/config/ui-lovelace.yaml` —
     * sensor + light + switch + lock + cover so entities / glance / stacks
     * have the same data HA sees. */
    val mixed = snapshot(
        state("sensor.living_room", "21.4",
            mapOf(
                "friendly_name" to "Living Room",
                "unit_of_measurement" to "°C",
                "device_class" to "temperature",
            )),
        state("light.kitchen", "on",
            mapOf("friendly_name" to "Kitchen", "brightness" to "200")),
        state("light.office_lamp", "off",
            mapOf("friendly_name" to "Office lamp")),
        state("switch.coffee_maker", "on",
            mapOf("friendly_name" to "Coffee maker")),
        state("lock.front_door", "locked",
            mapOf("friendly_name" to "Front door")),
        state("cover.living_room_window", "open",
            mapOf("friendly_name" to "Living Room Window", "device_class" to "window")),
    )

    val battery = snapshot(
        state("sensor.repeater_battery", "62",
            mapOf(
                "friendly_name" to "Repeater battery",
                "unit_of_measurement" to "%",
                "device_class" to "battery",
            )),
    )

    val weather = snapshot(
        weatherStateWithForecast(
            entityId = "weather.forecast_home",
            condition = "partlycloudy",
            attrs = mapOf(
                "friendly_name" to "Forecast Home",
                "temperature" to "11.2",
                "temperature_unit" to "°C",
            ),
            forecast = listOf(
                forecastDay("2026-05-07", "partlycloudy", high = 13.4, low = 7.5),
                forecastDay("2026-05-08", "rainy",        high = 11.9, low = 6.8),
                forecastDay("2026-05-09", "cloudy",       high = 12.2, low = 8.1),
                forecastDay("2026-05-10", "sunny",        high = 16.7, low = 9.0),
                forecastDay("2026-05-11", "lightning-rainy", high = 14.0, low = 7.3),
            ),
        ),
    )

    val driveway = snapshot(
        state("camera.driveway", "streaming",
            mapOf("friendly_name" to "Driveway")),
    )

    val activity = snapshot(
        state("binary_sensor.front_door", "off",
            mapOf("friendly_name" to "Front door", "device_class" to "door")),
        state("binary_sensor.garage_motion", "on",
            mapOf("friendly_name" to "Garage motion", "device_class" to "motion")),
    )
}
