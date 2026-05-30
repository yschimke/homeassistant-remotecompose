package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Best-effort extraction of the Home Assistant entity ids a card refers
 * to, used by the card-history screen to know what to chart. Covers the
 * shapes the common card types use:
 *
 *   - `entity: sensor.x` (tile, button, gauge, single-entity cards)
 *   - `entities: [sensor.a, light.b]` (entities / glance / history-graph,
 *     bare-string form)
 *   - `entities: [{ entity: sensor.a }, …]` (object form with per-row
 *     overrides)
 *
 * Order is preserved and duplicates are removed so a card that names the
 * same entity twice (e.g. once at top level, once in a row) charts it
 * once. Cards with no recognisable entity reference yield an empty list;
 * the history screen then shows a "not linked to an entity" message
 * rather than an empty chart.
 */
fun CardConfig.historyEntityIds(): List<String> {
    val ids = LinkedHashSet<String>()
    raw["entity"]?.jsonPrimitive?.contentOrNull?.let { if (it.isEntityId()) ids += it }
    (raw["entities"] as? JsonArray)?.forEach { entry ->
        when (entry) {
            is JsonPrimitive -> entry.contentOrNull?.let { if (it.isEntityId()) ids += it }
            is JsonObject -> entry["entity"]?.jsonPrimitive?.contentOrNull
                ?.let { if (it.isEntityId()) ids += it }
            else -> Unit
        }
    }
    return ids.toList()
}

/**
 * A human-readable title for the card, reusing the same key precedence
 * the pin/section code uses (`title` → `heading` → `name`) before
 * falling back to the entity id or the raw card type.
 */
fun CardConfig.historyTitle(): String =
    raw["title"]?.jsonPrimitive?.contentOrNull
        ?: raw["heading"]?.jsonPrimitive?.contentOrNull
        ?: raw["name"]?.jsonPrimitive?.contentOrNull
        ?: historyEntityIds().firstOrNull()
        ?: type

/** A string looks like an HA entity id when it's `domain.object_id`. */
private fun String.isEntityId(): Boolean {
    val dot = indexOf('.')
    return dot in 1 until length - 1
}
