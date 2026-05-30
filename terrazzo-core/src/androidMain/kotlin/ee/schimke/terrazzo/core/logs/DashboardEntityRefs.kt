package ee.schimke.terrazzo.core.logs

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Heuristic entity-id extractor for dashboard configs.
 *
 * Lovelace card schemas don't share a single field for "the entities I
 * use" — most cards put one or more `entity` / `entities` keys at the
 * top level, but custom cards bury entity refs anywhere in their JSON
 * (e.g. `triggers[].entity_id`, `tap_action.entity_id`). Walking the
 * full card tree and picking out strings that look like an HA entity id
 * covers the long tail without us hardcoding per-card knowledge.
 *
 * The HA entity-id grammar is restrictive enough
 * (`^[a-z0-9_]+\.[a-z0-9_]+$`) that false positives are rare in
 * practice — icon names use `mdi:` (colon, not dot), template strings
 * have whitespace, etc.
 */
fun referencedEntities(dashboard: Dashboard): Set<String> {
    val out = mutableSetOf<String>()
    for (view in dashboard.views) {
        view.cards.forEach { walk(it.raw, out) }
        view.sections.forEach { sec -> sec.cards.forEach { walk(it.raw, out) } }
        view.badges.forEach { walk(it, out) }
    }
    return out
}

/**
 * Same heuristic as the dashboard-level [referencedEntities], scoped to
 * a single card's config. Used by the per-card debug surfaces (flash on
 * data change) that need to know which entities one card slot depends
 * on. Returns entity ids in no particular order.
 */
fun referencedEntities(card: CardConfig): Set<String> {
    val out = mutableSetOf<String>()
    walk(card.raw, out)
    return out
}

private fun walk(el: JsonElement, out: MutableSet<String>) {
    when (el) {
        is JsonObject -> el.values.forEach { walk(it, out) }
        is JsonArray -> el.forEach { walk(it, out) }
        is JsonPrimitive -> if (el.isString) {
            val s = el.content
            if (ENTITY_ID_REGEX.matches(s)) out.add(s)
        }
    }
}

private val ENTITY_ID_REGEX = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
