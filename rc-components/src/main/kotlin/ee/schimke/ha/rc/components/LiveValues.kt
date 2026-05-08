@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs

/**
 * Bridge from plain Kotlin types to RemoteCompose named bindings,
 * applied inside the `RemoteHa*` wrappers.
 *
 * Component data classes carry the underlying entity (`entityId`) plus
 * initial values as Kotlin `String` / `Boolean`. The wrapper combines
 * the entity id with a per-field suffix (`state`, `is_on`,
 * `attributes.<key>`, …) and creates a named `RemoteString` /
 * `RemoteBoolean` in the [RemoteState.Domain.User] domain. A running
 * player updates by name; the captured document bytes don't move.
 *
 * When [entityId] is null (preview, unmatched entity), the wrapper
 * falls back to a constant value — no binding name, no host-side
 * write contract.
 *
 * Naming convention is one source of truth: the wrappers always pick
 * the suffix per field. Hosts pushing updates know that
 * `<entityId>.state` is the entity's primary state, `<entityId>.is_on`
 * is the active flag, and `<entityId>.attributes.<key>` mirrors HA's
 * attribute namespace.
 */
object LiveValues {

    private fun name(entityId: String, suffix: String): String = "$entityId.$suffix"

    /** Entity primary state ↔ `<entityId>.state`. */
    fun state(entityId: String?, initial: String): RemoteString =
        named(entityId, "state", initial)

    /**
     * Entity primary state as an integer key ↔ `<entityId>.state_int`. The
     * caller picks a stable `String → Int` mapping for the relevant HA
     * domain (alarm-panel, climate hvac_mode, …); the host pushes the
     * matching int when the entity changes and `RemoteStateLayout(RemoteInt,
     * …)` flips the variant without a re-encode. When [entityId] is null
     * the helper falls back to a constant.
     */
    fun intState(entityId: String?, initial: Int): RemoteInt =
        namedInt(entityId, "state_int", initial)

    /**
     * Generic integer host binding. The caller picks the suffix; useful
     * for any enum-shaped state that isn't the primary `state` (e.g.
     * mode indices). When [entityId] is null the helper falls back to
     * a constant.
     */
    fun namedInt(entityId: String?, suffix: String, initial: Int): RemoteInt {
        if (entityId == null) return initial.ri
        return RemoteInt.createNamedRemoteInt(
            name(entityId, suffix),
            initial,
            RemoteState.Domain.User,
        )
    }

    /** Entity active flag ↔ `<entityId>.is_on`. */
    fun isOn(entityId: String?, initial: Boolean): RemoteBoolean? {
        if (entityId == null) return null
        return RemoteBoolean.createNamedRemoteBoolean(
            name(entityId, "is_on"),
            initial,
            RemoteState.Domain.User,
        )
    }

    /** Entity attribute ↔ `<entityId>.attributes.<attribute>`. */
    fun attribute(entityId: String?, attribute: String, initial: String): RemoteString =
        named(entityId, "attributes.$attribute", initial)

    /**
     * Entity numeric state ↔ `<entityId>.numeric_state` — the parsed
     * `Float` form of the primary state, used by gauges / arcs that
     * tween value changes via [AnimatedRemoteFloat]. The host pushes
     * each new value by name; the player tweens between them.
     */
    fun numericState(entityId: String?, initial: Float): RemoteFloat =
        namedFloat(entityId, "numeric_state", initial)

    /**
     * Generic numeric host binding for components that need to react to
     * value updates without a re-encode (`valueFraction`,
     * `targetFraction`, etc.). The caller picks the suffix; the host
     * pushes by `<entityId>.<suffix>`.
     */
    fun namedFloat(entityId: String?, suffix: String, initial: Float): RemoteFloat {
        if (entityId == null) return initial.rf
        return RemoteFloat.createNamedRemoteFloat(
            name(entityId, suffix),
            initial,
            RemoteState.Domain.User,
        )
    }

    /**
     * One sample of an entity's history series ↔ `<entityId>.numeric.<index>`.
     * Sparkline-style cards bind each captured point individually so the
     * host can push a sliding window of values without re-encoding the
     * document. The renderer uses the captured float values to fix the
     * y-axis range at encode time.
     */
    fun numericPoint(entityId: String?, index: Int, initial: Float): RemoteFloat =
        namedFloat(entityId, "numeric.$index", initial)

    /**
     * Convenience for a whole history series — one named binding per
     * index (`<entityId>.numeric.0`, `…1`, `…2`, …). When [entityId] is
     * null each entry falls back to a constant `RemoteFloat`.
     */
    fun numericPoints(entityId: String?, points: List<Float>): List<RemoteFloat> =
        points.mapIndexed { i, v -> numericPoint(entityId, i, v) }

    /**
     * Generic helper for fields whose binding name doesn't follow the
     * `state` / `is_on` / `attributes.*` convention. The caller picks
     * the suffix; useful for composite labels (e.g. `state_label`,
     * `range_label`) where the host pushes a pre-formatted string
     * instead of the raw HA value.
     */
    fun named(entityId: String?, suffix: String, initial: String): RemoteString {
        if (entityId == null) return initial.rs
        return RemoteString.createNamedRemoteString(
            name(entityId, suffix),
            initial,
            RemoteState.Domain.User,
        )
    }
}
