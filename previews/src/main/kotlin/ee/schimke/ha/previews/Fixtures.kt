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

    /** Bambu Lab printer mid-print: progress, layer counts, time
     *  remaining, plus nozzle/bed temperatures. The entity prefix
     *  matches HA's `bambulab` integration scheme so the converter's
     *  prefix-discovery picks it up automatically. */
    val bambuPrinting = snapshot(
        state("sensor.p2s_printing_print_progress", "34",
            mapOf(
                "friendly_name" to "P2S Print progress",
                "unit_of_measurement" to "%",
            )),
        state("sensor.p2s_printing_current_stage", "inspecting_first_layer",
            mapOf("friendly_name" to "P2S Current stage")),
        state("sensor.p2s_printing_print_status", "printing",
            mapOf("friendly_name" to "P2S Print status")),
        state("sensor.p2s_printing_current_layer", "12",
            mapOf("friendly_name" to "P2S Current layer")),
        state("sensor.p2s_printing_total_layer_count", "240",
            mapOf("friendly_name" to "P2S Total layer count")),
        state("sensor.p2s_printing_remaining_time", "82",
            mapOf("friendly_name" to "P2S Remaining time", "unit_of_measurement" to "min")),
        state("sensor.p2s_printing_nozzle_temperature", "218.4",
            mapOf("friendly_name" to "P2S Nozzle temperature", "unit_of_measurement" to "°C")),
        state("sensor.p2s_printing_target_nozzle_temperature", "220",
            mapOf("friendly_name" to "P2S Target nozzle temperature", "unit_of_measurement" to "°C")),
        state("sensor.p2s_printing_bed_temperature", "60",
            mapOf("friendly_name" to "P2S Bed temperature", "unit_of_measurement" to "°C")),
        state("sensor.p2s_printing_target_bed_temperature", "60",
            mapOf("friendly_name" to "P2S Target bed temperature", "unit_of_measurement" to "°C")),
    )

    /** Two temperature sensors with a 24-sample diurnal cycle so the
     *  history-graph preview has real sparkline data to draw. */
    val temperatureHistory: HaSnapshot = run {
        val outsideStates = listOf("sensor.outside_temp", "sensor.upstairs_temp")
        val states = mapOf(
            "sensor.outside_temp" to EntityState(
                entityId = "sensor.outside_temp",
                state = "8.2",
                attributes = JsonObject(mapOf(
                    "friendly_name" to JsonPrimitive("Outside"),
                    "unit_of_measurement" to JsonPrimitive("°C"),
                    "device_class" to JsonPrimitive("temperature"),
                )),
            ),
            "sensor.upstairs_temp" to EntityState(
                entityId = "sensor.upstairs_temp",
                state = "22.2",
                attributes = JsonObject(mapOf(
                    "friendly_name" to JsonPrimitive("Upstairs"),
                    "unit_of_measurement" to JsonPrimitive("°C"),
                    "device_class" to JsonPrimitive("temperature"),
                )),
            ),
        )
        // Synthetic 24h sample series: outside follows a sine-ish day,
        // upstairs is flatter with mild dips overnight.
        val outside = listOf(
            7.1f, 6.4f, 5.8f, 5.4f, 5.6f, 6.5f, 8.0f, 9.6f,
            11.0f, 12.4f, 13.5f, 14.1f, 14.4f, 14.0f, 13.5f, 12.6f,
            11.4f, 10.2f, 9.4f, 8.9f, 8.6f, 8.4f, 8.3f, 8.2f,
        )
        val upstairs = listOf(
            21.5f, 21.3f, 21.2f, 21.0f, 20.9f, 21.0f, 21.4f, 21.9f,
            22.4f, 22.7f, 22.9f, 23.0f, 23.1f, 23.0f, 22.9f, 22.8f,
            22.6f, 22.5f, 22.4f, 22.3f, 22.3f, 22.2f, 22.2f, 22.2f,
        )
        val baseTime = kotlinx.datetime.Instant.parse("2026-05-05T00:00:00Z")
        fun series(name: String, samples: List<Float>) =
            samples.mapIndexed { i, v ->
                ee.schimke.ha.model.HistoryPoint(
                    ts = baseTime.plus(kotlin.time.Duration.parse("${i}h")),
                    state = v.toString(),
                )
            }
        HaSnapshot(
            states = states,
            history = mapOf(
                "sensor.outside_temp" to series("Outside", outside),
                "sensor.upstairs_temp" to series("Upstairs", upstairs),
            ),
        )
    }
}
