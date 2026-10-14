package ee.schimke.ha.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Host-side derivations for the live named-binding wire contract.
 *
 * A captured `.rc` document carries named bindings (`<entityId>.state`, `<entityId>.is_on`,
 * `<entityId>.numeric_state`, `<entityId>.state_int`, …) that a running player updates by name
 * without a re-encode. Every host that pushes those updates — the in-process dashboard player
 * (`CachedCardPreview`) and the add-on server stream (`StreamRoute`) — must derive the same values
 * from the same entity state, or the same document would show different things depending on who fed
 * it.
 *
 * This object is that single source of truth for the *value* side of the contract. The *name* side
 * lives in `LiveValues` (rc-components), which bakes the matching names into the document at
 * capture time.
 *
 * Keep these in lockstep: a binding a converter bakes is only "live" if the host can reproduce its
 * value here from the snapshot alone (no card config). Bindings whose value is formatted, derived,
 * or structural — anything the host can't reproduce — must instead be refreshed by a document
 * re-encode (see `CardConverter.dataSignature`).
 */
object HaLiveBindings {

  /**
   * Whether the entity is in its active / on-like state ↔ `<entityId>.is_on`. Converters bake the
   * initial accent from [HaEntity.isActive], so the pushed value must come from the same place: a
   * heating thermostat, an open cover, a locked lock or a playing media player is active even
   * though its state isn't literally `"on"`. Domains without a modelled active concept (valves,
   * scenes, …) fall back to `"on"` or `"open"`. Converters seed `<id>.is_on` from this same
   * function, so the first push never changes an accent the document already shows.
   */
  fun isOn(entityId: String, state: String): Boolean =
    EntityState(entityId, state).toTyped().isActive ?: (state == "on" || state == "open")

  /**
   * Parsed numeric form of an entity's primary state ↔ `<entityId>.numeric_state`. Gauges / arcs
   * bind this and compute their sweep in-document, so pushing the raw number keeps them live
   * without a re-encode. Non-numeric or non-finite states (e.g. `"on"`, `"unavailable"`) have no
   * numeric form and return null.
   */
  fun numericState(state: String): Float? = state.toFloatOrNull()?.takeIf { it.isFinite() }

  /**
   * Domain-specific integer key for an entity's state ↔ `<entityId>.state_int`. The document drives
   * a `RemoteStateLayout(RemoteInt, …)` variant off this, flipping chrome (alarm armed/disarmed, …)
   * without a re-encode. Only domains with a stable `String → Int` wire mapping return a value;
   * everything else returns null and the host pushes no int.
   */
  fun stateInt(entityId: String, state: String): Int? =
    when (entityId.substringBefore('.', missingDelimiterValue = "")) {
      "alarm_control_panel" -> alarmStateIntFromRaw(state)
      else -> null
    }

  /**
   * Numeric attributes a domain publishes as their own float bindings, by binding suffix:
   * `<entityId>.<suffix>`. A climate entity's state is its mode (`heat`), so a thermostat dial
   * can't sweep on `numeric_state`; it binds `current_temperature` and `target_temperature` instead
   * and computes its fraction and labels in-document. Attributes that are missing or not numeric
   * have no binding.
   */
  fun attributeFloats(entityId: String, attributes: JsonObject): Map<String, Float> {
    val keys = AttributeFloats[entityId.substringBefore('.', missingDelimiterValue = "")]
    if (keys.isNullOrEmpty()) return emptyMap()
    return buildMap {
      for ((suffix, attribute) in keys) {
        val value = (attributes[attribute] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
        if (value != null && value.isFinite()) put(suffix, value)
      }
    }
  }

  /** Binding suffix → attribute, per domain, for [attributeFloats]. */
  private val AttributeFloats: Map<String, Map<String, String>> =
    mapOf(
      "climate" to
        mapOf(
          CURRENT_TEMPERATURE to "current_temperature",
          TARGET_TEMPERATURE to "temperature",
        )
    )

  /** `<climate>.current_temperature`: the room temperature a thermostat reads. */
  const val CURRENT_TEMPERATURE: String = "current_temperature"

  /** `<climate>.target_temperature`: the set point (HA's `temperature` attribute). */
  const val TARGET_TEMPERATURE: String = "target_temperature"
}
