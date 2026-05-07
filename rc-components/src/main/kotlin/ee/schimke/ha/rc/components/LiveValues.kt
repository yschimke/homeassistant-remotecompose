@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.RemoteState
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
