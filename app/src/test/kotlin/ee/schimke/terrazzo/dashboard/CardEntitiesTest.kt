package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class CardEntitiesTest {

    private fun card(build: JsonObjectBuilder.() -> Unit): CardConfig {
        val raw = buildJsonObject(build)
        val type = raw["type"]?.jsonPrimitive?.content ?: "unknown"
        return CardConfig(type = type, raw = raw)
    }

    @Test
    fun extractsSingleEntity() {
        val c = card {
            put("type", "tile")
            put("entity", "sensor.living_room_temperature")
        }
        assertEquals(listOf("sensor.living_room_temperature"), c.historyEntityIds())
    }

    @Test
    fun extractsEntitiesArrayBothForms() {
        val c = card {
            put("type", "entities")
            putJsonArray("entities") {
                add("light.kitchen")
                addJsonObject { put("entity", "switch.fan") }
            }
        }
        assertEquals(listOf("light.kitchen", "switch.fan"), c.historyEntityIds())
    }

    @Test
    fun dedupesAndDropsNonEntityStrings() {
        val c = card {
            put("type", "entities")
            put("entity", "sensor.power")
            putJsonArray("entities") {
                add("sensor.power") // duplicate of top-level entity
                add("not an entity id")
                add("climate.bedroom")
            }
        }
        assertEquals(listOf("sensor.power", "climate.bedroom"), c.historyEntityIds())
    }

    @Test
    fun emptyWhenNoEntityReference() {
        val c = card {
            put("type", "markdown")
            put("content", "# notes")
        }
        assertEquals(emptyList(), c.historyEntityIds())
    }

    @Test
    fun titlePrefersTitleThenEntity() {
        assertEquals(
            "Boiler",
            card {
                put("type", "tile")
                put("title", "Boiler")
                put("entity", "sensor.boiler")
            }.historyTitle(),
        )
        assertEquals(
            "sensor.boiler",
            card {
                put("type", "tile")
                put("entity", "sensor.boiler")
            }.historyTitle(),
        )
        assertEquals(
            "markdown",
            card { put("type", "markdown") }.historyTitle(),
        )
    }
}
