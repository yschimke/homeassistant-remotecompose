package ee.schimke.ha.previews

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
}
