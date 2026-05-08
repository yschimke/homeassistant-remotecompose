package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Recursively walks a Lovelace card's raw config to discover the
 * `entity_id`s the card consumes, so a host can push named-binding
 * updates only for what the card actually reads.
 *
 * Recognises the two HA-standard fields:
 *   - `entity:` — string value, single entity.
 *   - `entities:` — array of strings, or array of objects each with an
 *     `entity:` key.
 *
 * Walks into nested `JsonObject` / `JsonArray` so stack / grid /
 * conditional / picture-elements / entity-filter cards expose the
 * entities of every nested card. Conservative: an unknown key with an
 * entity-id-shaped value is NOT picked up; only the documented
 * Lovelace shape. Values without a `domain.id` dot are skipped to
 * avoid catching `name:`-shaped strings that happen to be in an
 * `entity` field.
 */
fun cardEntityIds(card: CardConfig): Set<String> {
    val out = LinkedHashSet<String>()
    collectEntityIds(card.raw, out)
    return out
}

private fun collectEntityIds(element: JsonElement, out: MutableSet<String>) {
    when (element) {
        is JsonObject -> {
            for ((key, value) in element) {
                when {
                    key == "entity" && value is JsonPrimitive -> addEntityId(value, out)
                    key == "entities" && value is JsonArray -> {
                        for (entry in value) {
                            when (entry) {
                                is JsonPrimitive -> addEntityId(entry, out)
                                is JsonObject -> collectEntityIds(entry, out)
                                else -> {}
                            }
                        }
                    }
                    else -> collectEntityIds(value, out)
                }
            }
        }
        is JsonArray -> element.forEach { collectEntityIds(it, out) }
        else -> {}
    }
}

private fun addEntityId(value: JsonPrimitive, out: MutableSet<String>) {
    val id = value.contentOrNull ?: return
    if (id.contains('.')) out.add(id)
}

/**
 * Named bindings for one card's [entityIds] under the current
 * [snapshot]. Mirrors the `addon-server` `/v1/stream` push shape
 * (see `StreamRoute.kt`): for each entity id present in the snapshot,
 * `<id>.state` (string) and `<id>.is_on` (bool) — the same names
 * `LiveValues` bakes into the document at capture time.
 *
 * Used by `CachedCardPreview` to push live updates into the running
 * player without re-encoding. Entities missing from the snapshot are
 * skipped: there's no defined "absent" value, and the document keeps
 * its initial bake until the entity reappears.
 */
data class CardSnapshotBindings(
    val strings: Map<String, String>,
    val booleans: Map<String, Boolean>,
)

fun cardSnapshotBindings(
    entityIds: Set<String>,
    snapshot: HaSnapshot,
): CardSnapshotBindings {
    if (entityIds.isEmpty()) return CardSnapshotBindings(emptyMap(), emptyMap())
    val strings = LinkedHashMap<String, String>(entityIds.size)
    val booleans = LinkedHashMap<String, Boolean>(entityIds.size)
    for (id in entityIds) {
        val state = snapshot.states[id] ?: continue
        strings["$id.state"] = state.state
        booleans["$id.is_on"] = state.state == "on"
    }
    return CardSnapshotBindings(strings, booleans)
}
