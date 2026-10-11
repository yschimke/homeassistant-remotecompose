package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Slot machinery shared by every card design: slot names, the slot → entity map, and the bindings a
 * template reads.
 *
 * A template is captured against **slot entity ids** (`<domain>.s<N>`, e.g. `light.s0`) instead of
 * real ones, so `LiveValues` bakes `light.s0.state`, `light.s0.is_on`, … The domain stays in the
 * slot id because converters derive structure from it. The client maps each slot back to the card's
 * real entity and pushes values under the slot names ([slotBindings]).
 *
 * Which cards can be templated, and with which generation properties, is each converter's call
 * (`CardConverter.designProperties`), not this object's.
 */
object CardDesignSlots {

  fun slotId(domain: String, index: Int): String = "$domain.s$index"

  fun domainOf(entityId: String): String = entityId.substringBefore('.', missingDelimiterValue = "")

  /** One bound entity of a card: its real id and the card's own name for it, if any. */
  data class SlotEntry(val entityId: String, val name: String?)

  /**
   * The entities [card] binds, in slot order: its `entity:` (named by the card's `name:`), or else
   * each `entities:` row — a bare entity id or `{ entity, name? }`. Null when it binds none.
   */
  fun slotEntries(card: CardConfig): List<SlotEntry>? {
    card.raw["entity"]?.jsonPrimitive?.contentOrNull?.let { id ->
      return listOf(SlotEntry(id, card.raw["name"]?.jsonPrimitive?.contentOrNull))
    }
    val rows = card.raw["entities"] as? JsonArray ?: return null
    return rows.mapNotNull { row ->
      when (row) {
        is JsonPrimitive -> row.contentOrNull?.let { SlotEntry(it, null) }
        is JsonObject ->
          row["entity"]?.jsonPrimitive?.contentOrNull?.let {
            SlotEntry(it, row["name"]?.jsonPrimitive?.contentOrNull)
          }
        else -> null
      }
    }
  }

  /** Real entity ids [card] binds, in slot order, or null. */
  fun slotEntities(card: CardConfig): List<String>? = slotEntries(card)?.map { it.entityId }

  /**
   * Map real entity id → slot entity id for [card]. A list that repeats an entity keeps its first
   * slot; [slotBindings] still fills every slot.
   */
  fun slotMap(card: CardConfig): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    slotEntries(card).orEmpty().forEachIndexed { i, e ->
      map.getOrPut(e.entityId) { slotId(domainOf(e.entityId), i) }
    }
    return map
  }

  /** The card-level title a template binds as `card.title`: `heading:` or `title:`. */
  fun cardTitle(card: CardConfig): String? =
    card.raw["heading"]?.jsonPrimitive?.contentOrNull
      ?: card.raw["title"]?.jsonPrimitive?.contentOrNull

  /**
   * A placeholder entity for template capture: the given [attributes] (generation properties a
   * converter maps back onto the entity) plus a placeholder name. Values only seed the named
   * bindings' initial values; the client overwrites every one of them.
   */
  fun templateEntity(
    slotId: String,
    placeholderState: String,
    attributes: Map<String, String> = emptyMap(),
  ): EntityState =
    EntityState(
      entityId = slotId,
      state = placeholderState,
      attributes =
        JsonObject(
          buildMap {
            put("friendly_name", JsonPrimitive(NAME_PLACEHOLDER))
            for ((k, v) in attributes) put(k, JsonPrimitive(v))
          }
        ),
    )

  /**
   * Every binding a template reads for [card], keyed by slot name. Unlike the entity-keyed
   * `cardSnapshotBindings`, every binding of every slot is always present: a value with no meaning
   * gets a neutral default, so nothing from a previous entity state lingers in the player, and
   * `<slot>.status` says why.
   */
  fun slotBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val strings = LinkedHashMap<String, String>()
    val booleans = LinkedHashMap<String, Boolean>()
    val ints = LinkedHashMap<String, Int>()
    val floats = LinkedHashMap<String, Float>()
    cardTitle(card)?.let { strings[CARD_TITLE] = it }
    slotEntries(card).orEmpty().forEachIndexed { i, entry ->
      val entityId = entry.entityId
      val slot = slotId(domainOf(entityId), i)
      val state = snapshot.states[entityId]
      val name =
        entry.name
          ?: state?.attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull
          ?: entityId
      strings["$slot.state"] = formatState(state)
      strings["$slot.name"] = name
      booleans["$slot.is_on"] = state?.let { HaLiveBindings.isOn(entityId, it.state) } ?: false
      ints["$slot.state_int"] = state?.let { HaLiveBindings.stateInt(entityId, it.state) } ?: -1
      ints["$slot.status"] = statusOf(state)
      floats["$slot.numeric_state"] =
        state?.let { HaLiveBindings.numericState(it.state) } ?: Float.NaN
    }
    return CardSnapshotBindings(strings, booleans, ints, floats)
  }

  /** `<slot>.status`: 0 available, 1 unavailable, 2 unknown, 3 entity missing. */
  fun statusOf(state: EntityState?): Int =
    when (state?.state) {
      null -> STATUS_MISSING
      "unavailable" -> STATUS_UNAVAILABLE
      "unknown" -> STATUS_UNKNOWN
      else -> STATUS_AVAILABLE
    }

  const val STATUS_AVAILABLE: Int = 0
  const val STATUS_UNAVAILABLE: Int = 1
  const val STATUS_UNKNOWN: Int = 2
  const val STATUS_MISSING: Int = 3

  /**
   * The image URL a template carries for [slotId]'s `entity_picture`, in place of the real one.
   * Playback resolves it against the card's own entity ([resolvePictureUrl]); a template can never
   * make the client fetch any other URL.
   */
  fun pictureUrl(slotId: String): String = "$SLOT_URL_SCHEME$slotId/$ENTITY_PICTURE"

  /**
   * The real `entity_picture` for a template URL from [pictureUrl], looking the slot up in [card]'s
   * own entities and [snapshot]; null for any URL that isn't a slot picture of this card.
   */
  fun resolvePictureUrl(url: String, card: CardConfig, snapshot: HaSnapshot?): String? {
    if (!url.startsWith(SLOT_URL_SCHEME)) return null
    val (slot, attribute) =
      url.removePrefix(SLOT_URL_SCHEME).split('/', limit = 2).takeIf { it.size == 2 } ?: return null
    if (attribute != ENTITY_PICTURE) return null
    val entityId = slotMap(card).entries.firstOrNull { it.value == slot }?.key ?: return null
    return snapshot
      ?.states
      ?.get(entityId)
      ?.attributes
      ?.get(ENTITY_PICTURE)
      ?.jsonPrimitive
      ?.contentOrNull
  }

  private const val SLOT_URL_SCHEME = "rc-slot://"
  private const val ENTITY_PICTURE = "entity_picture"

  /** Card-level title binding; matches `CARD_TITLE_BINDING` in rc-components. */
  const val CARD_TITLE: String = "card.title"

  /** Placeholder name baked into a template; the `<slot>.name` binding replaces it. */
  const val NAME_PLACEHOLDER: String = "Name"
}
