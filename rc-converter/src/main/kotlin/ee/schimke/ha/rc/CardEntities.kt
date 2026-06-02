package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Recursively walks a Lovelace card's raw config to discover the `entity_id`s the card consumes, so
 * a host can push named-binding updates only for what the card actually reads.
 *
 * Recognises the two HA-standard fields:
 * - `entity:` — string value, single entity.
 * - `entities:` — array of strings, or array of objects each with an `entity:` key.
 *
 * Walks into nested `JsonObject` / `JsonArray` so stack / grid / conditional / picture-elements /
 * entity-filter cards expose the entities of every nested card. Conservative: an unknown key with
 * an entity-id-shaped value is NOT picked up; only the documented Lovelace shape. Values without a
 * `domain.id` dot are skipped to avoid catching `name:`-shaped strings that happen to be in an
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
 * Named bindings for one card's [entityIds] under the current [snapshot]. Mirrors the
 * `addon-server` `/v1/stream` push shape (see `StreamRoute.kt`) and the names `LiveValues` bakes
 * into the document at capture time. For each entity id present in the snapshot:
 * - `<id>.state` (string) — the **formatted** display string, matching `formatState` (what the
 *   converters bake), not the raw HA state. Pushing the raw state would replace e.g. `"21.5 °C"`
 *   with `"21.5"` on the first update.
 * - `<id>.is_on` (bool) — active flag.
 * - `<id>.state_int` (int) — domain-keyed state variant, where one exists
 *   ([HaLiveBindings.stateInt]); used by alarm-panel chrome.
 * - `<id>.numeric_state` (float) — parsed numeric state ([HaLiveBindings.numericState]); used by
 *   gauges / arcs that derive their sweep in-document.
 *
 * This is the value side of the live-binding contract: every binding here is host-reproducible from
 * the snapshot alone. Bindings whose value is formatted per-card, derived, or structural (forecast
 * strips, history sparklines, list contents, arc fill fractions) can't be reproduced here — those
 * cards opt into a document re-encode instead (see `CardConverter.dataSignature`).
 *
 * Used by `CachedCardPreview` to push live updates into the running player without re-encoding.
 * Entities missing from the snapshot are skipped: there's no defined "absent" value, and the
 * document keeps its initial bake until the entity reappears.
 */
data class CardSnapshotBindings(
  val strings: Map<String, String>,
  val booleans: Map<String, Boolean>,
  val ints: Map<String, Int>,
  val floats: Map<String, Float>,
)

fun cardSnapshotBindings(entityIds: Set<String>, snapshot: HaSnapshot): CardSnapshotBindings {
  if (entityIds.isEmpty()) {
    return CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())
  }
  val strings = LinkedHashMap<String, String>(entityIds.size)
  val booleans = LinkedHashMap<String, Boolean>(entityIds.size)
  val ints = LinkedHashMap<String, Int>(entityIds.size)
  val floats = LinkedHashMap<String, Float>(entityIds.size)
  for (id in entityIds) {
    val state = snapshot.states[id] ?: continue
    strings["$id.state"] = formatState(state)
    booleans["$id.is_on"] = state.state == "on"
    HaLiveBindings.stateInt(id, state.state)?.let { ints["$id.state_int"] = it }
    HaLiveBindings.numericState(state.state)?.let { floats["$id.numeric_state"] = it }
  }
  return CardSnapshotBindings(strings, booleans, ints, floats)
}

/**
 * A coarse fingerprint of the snapshot-derived content a card *bakes* into its document, for cards
 * that can't express their live data as host-pushable named bindings (see [cardSnapshotBindings]).
 * Folded into the card's render cache key by the dashboard, so the document re-encodes whenever the
 * baked content moves — the only update path for structural / derived / per-card-formatted cards.
 *
 * Covers everything a converter might read for [entityIds]: primary state, last-updated,
 * attributes, and the history / statistics / forecast slices of the snapshot. Over-inclusion only
 * costs a redundant (identical) re-encode, so this stays deliberately broad rather than trying to
 * know which slice each card reads.
 */
fun cardDataSignature(entityIds: Set<String>, snapshot: HaSnapshot): String {
  if (entityIds.isEmpty()) return ""
  return buildString {
    for (id in entityIds) {
      val s = snapshot.states[id]
      append(id).append('=').append(s?.state)
      append('@').append(s?.lastUpdated)
      append('#').append(s?.attributes?.hashCode())
      append("~h").append(snapshot.history[id]?.hashCode())
      append("~s").append(snapshot.statistics[id]?.hashCode())
      append("~f").append(snapshot.forecasts[id]?.hashCode())
      append(';')
    }
  }
}

/**
 * Recursively walks [card] for `type: picture-entity` nodes (including picture-entity cards nested
 * in stack / grid / conditional / horizontal / vertical layouts) and returns the `entity:`
 * referenced by each. Used by `CachedCardPreview` to discover which slots need a live bitmap
 * override when `entity_picture` rotates.
 *
 * The set is ordered by first appearance for stable iteration. Entries without a `domain.id`-shaped
 * value are skipped (mirrors [cardEntityIds]).
 */
fun cardPictureEntityIds(card: CardConfig): Set<String> {
  val out = LinkedHashSet<String>()
  collectPictureEntities(card.raw, out)
  return out
}

private fun collectPictureEntities(element: JsonElement, out: MutableSet<String>) {
  when (element) {
    is JsonObject -> {
      val type = element["type"]?.let { it as? JsonPrimitive }?.contentOrNull
      if (type == "picture-entity") {
        val entity = element["entity"]?.let { it as? JsonPrimitive }?.contentOrNull
        if (entity != null && entity.contains('.')) out.add(entity)
      }
      for ((_, value) in element) collectPictureEntities(value, out)
    }
    is JsonArray -> element.forEach { collectPictureEntities(it, out) }
    else -> {}
  }
}
