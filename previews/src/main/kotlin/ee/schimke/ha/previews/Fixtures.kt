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

/** A small bank of fixture snapshots used across previews. */
object Fixtures {
    val livingRoomTemp = snapshot(
        state("sensor.living_room_temperature", "21.4",
            mapOf("friendly_name" to "Living Room", "unit_of_measurement" to "°C")),
    )

    val kitchenLight = snapshot(
        state("light.kitchen", "on",
            mapOf("friendly_name" to "Kitchen", "brightness" to "200")),
    )
}
