package ee.schimke.ha.model

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
 * lives in `LiveValues` (rc-components), which bakes the matching names into the document at capture
 * time.
 *
 * Keep these in lockstep: a binding a converter bakes is only "live" if the host can reproduce its
 * value here from the snapshot alone (no card config). Bindings whose value is formatted, derived,
 * or structural — anything the host can't reproduce — must instead be refreshed by a document
 * re-encode (see `CardConverter.dataSignature`).
 */
object HaLiveBindings {

  /**
   * Parsed numeric form of an entity's primary state ↔ `<entityId>.numeric_state`. Gauges / arcs
   * bind this and compute their sweep in-document, so pushing the raw number keeps them live without
   * a re-encode. Non-numeric or non-finite states (e.g. `"on"`, `"unavailable"`) have no numeric
   * form and return null.
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
}
