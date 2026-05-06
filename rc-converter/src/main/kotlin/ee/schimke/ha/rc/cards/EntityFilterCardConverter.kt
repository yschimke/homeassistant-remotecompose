package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `entity-filter` card — filters the configured entity list against a
 * `state_filter:` (each filter is either a string or
 * `{ value: ..., operator: ==|!=|<|>|>=|<= }`) and forwards the kept
 * entities to a sub-card (defaulting to `glance`). Uses the existing
 * GlanceCardConverter / EntitiesCardConverter / TileCardConverter
 * machinery rather than rendering its own visual.
 */
class EntityFilterCardConverter : CardConverter {
    override val cardType: String = CardTypes.ENTITY_FILTER

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 150

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val ids = filteredEntities(card, snapshot)
        val cardCfg = card.raw["card"] as? JsonObject
        val targetType = cardCfg?.get("type")?.jsonPrimitive?.content ?: "glance"
        val title = cardCfg?.get("title")?.jsonPrimitive?.content ?: "Entity filter"
        val mergedRaw = buildJsonObject {
            put("type", JsonPrimitive(targetType))
            put("title", JsonPrimitive(title))
            put("entities", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            cardCfg?.entries?.forEach { (k, v) ->
                if (k != "type" && k != "title" && k != "entities") put(k, v)
            }
        }
        val sub = ee.schimke.ha.model.CardConfig(type = targetType, raw = mergedRaw)
        val converter: CardConverter = when (targetType) {
            "glance" -> GlanceCardConverter()
            "entities" -> EntitiesCardConverter()
            "tile" -> TileCardConverter()
            else -> GlanceCardConverter()
        }
        converter.Render(sub, snapshot, modifier)
    }
}

private fun filteredEntities(card: CardConfig, snapshot: HaSnapshot): List<String> {
    val arr = card.raw["entities"] as? JsonArray ?: return emptyList()
    val filters = (card.raw["state_filter"] as? JsonArray)?.toList() ?: emptyList()
    return arr.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.content
            is JsonObject -> el["entity"]?.jsonPrimitive?.content
            else -> null
        }
    }.filter { id ->
        val state = snapshot.states[id]?.state ?: return@filter false
        if (filters.isEmpty()) state == "on" || state == "open"
        else filters.any { matches(state, it) }
    }
}

private fun matches(state: String, filter: kotlinx.serialization.json.JsonElement): Boolean {
    return when (filter) {
        is JsonPrimitive -> filter.content == state
        is JsonObject -> {
            val value = filter["value"]?.jsonPrimitive?.content ?: return false
            val op = filter["operator"]?.jsonPrimitive?.content ?: "=="
            when (op) {
                "==" -> state == value
                "!=" -> state != value
                ">", "<", ">=", "<=" -> {
                    val a = state.toDoubleOrNull() ?: return false
                    val b = value.toDoubleOrNull() ?: return false
                    when (op) {
                        ">" -> a > b
                        "<" -> a < b
                        ">=" -> a >= b
                        "<=" -> a <= b
                        else -> false
                    }
                }
                "regex" -> Regex(value).matches(state)
                else -> false
            }
        }
        else -> false
    }
}
