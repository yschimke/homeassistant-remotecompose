package ee.schimke.ha.design

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.formatState
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

  /** Real entity ids a single-entity card binds, in slot order (its `entity:`), or null. */
  fun slotEntities(card: CardConfig): List<String>? =
    card.raw["entity"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) }

  /** Map real entity id → slot entity id for [card]. */
  fun slotMap(card: CardConfig): Map<String, String> =
    slotEntities(card).orEmpty().mapIndexed { i, id -> id to slotId(domainOf(id), i) }.toMap()

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
    for ((entityId, slot) in slotMap(card)) {
      val state = snapshot.states[entityId]
      val name =
        card.raw["name"]?.jsonPrimitive?.contentOrNull
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

  /** Placeholder name baked into a template; the `<slot>.name` binding replaces it. */
  const val NAME_PLACEHOLDER: String = "Name"
}
