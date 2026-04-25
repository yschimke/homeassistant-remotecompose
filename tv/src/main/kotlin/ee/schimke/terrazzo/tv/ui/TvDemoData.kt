package ee.schimke.terrazzo.tv.ui

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * TV-only demo fixture. Mirrors the phone's `DemoData` in shape — a
 * handful of HA-style cards plus an animated snapshot — so we can run
 * the *same* CardConverter pipeline the phone uses and render real
 * RemoteCompose documents on the TV.
 *
 * Living in the TV module keeps it independent of `terrazzo-core`; the
 * TV only needs `ha-model` + `rc-converter` + `rc-components`.
 */
object TvDemoData {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A row's worth of cards for the kiosk preview. */
    val cards: List<CardConfig> = listOf(
        card("""{"type":"heading","heading":"Living room"}"""),
        card("""{"type":"glance","title":"Overview","entities":[
            "sensor.living_room",
            "light.kitchen",
            "lock.front_door"
        ]}"""),
        card("""{"type":"entities","title":"Environment","entities":[
            "sensor.living_room",
            "sensor.humidity",
            "sensor.power"
        ]}"""),
        card("""{"type":"horizontal-stack","cards":[
            {"type":"tile","entity":"light.kitchen","color":"amber"},
            {"type":"tile","entity":"lock.front_door"}
        ]}"""),
    )

    fun snapshot(nowMs: Long = System.currentTimeMillis()): HaSnapshot {
        val tSec = nowMs / 1000.0
        val livingTemp = 21.0 + 2.0 * sin(tSec / 60.0)
        val humidity = (48.0 + 10.0 * sin(tSec / 20.0)).roundToInt()
        val power = (170.0 + 90.0 * sin(tSec / 7.0)).roundToInt()
        val officeLampOn = (nowMs / 8_000L) % 2L == 0L
        val hallwayOn = (nowMs / 12_000L) % 3L == 0L

        return HaSnapshot(
            states = mapOf(
                entity(
                    "sensor.living_room", "%.1f".format(livingTemp),
                    "friendly_name" to "Living Room",
                    "unit_of_measurement" to "°C",
                    "device_class" to "temperature",
                ),
                entity(
                    "sensor.humidity", humidity.toString(),
                    "friendly_name" to "Humidity",
                    "unit_of_measurement" to "%",
                    "device_class" to "humidity",
                ),
                entity(
                    "sensor.power", power.toString(),
                    "friendly_name" to "Power",
                    "unit_of_measurement" to "W",
                    "device_class" to "power",
                ),
                entity(
                    "light.kitchen", "on",
                    "friendly_name" to "Kitchen",
                    "brightness" to "220",
                ),
                entity(
                    "light.office_lamp", if (officeLampOn) "on" else "off",
                    "friendly_name" to "Office lamp",
                ),
                entity(
                    "light.hallway", if (hallwayOn) "on" else "off",
                    "friendly_name" to "Hallway",
                ),
                entity(
                    "lock.front_door", "locked",
                    "friendly_name" to "Front door",
                ),
            ),
        )
    }

    private fun card(src: String): CardConfig {
        val obj = json.parseToJsonElement(src).jsonObject
        val type = obj["type"]!!.toString().trim('"')
        return CardConfig(type = type, raw = obj)
    }

    private fun entity(
        id: String,
        state: String,
        vararg attrs: Pair<String, String>,
    ): Pair<String, EntityState> {
        val obj = JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) })
        return id to EntityState(entityId = id, state = state, attributes = obj)
    }
}
