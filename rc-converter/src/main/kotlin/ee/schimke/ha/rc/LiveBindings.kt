@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rs
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaEntity
import ee.schimke.ha.model.toTyped

/**
 * Named state bindings that a running host can update at playback time
 * without re-encoding the document.
 *
 * RC's `RemoteBoolean` / `RemoteString` participate in expressions like
 * `isOn.select(onColor, offColor)` — when the host updates the named
 * state, the expression re-evaluates and the player re-draws.
 *
 * We use the `User` domain and key bindings by `entity_id` plus a suffix
 * (`is_on`, `state`, ...). A widget runtime subscribes to HA state
 * changes for that entity and pushes the new value by name. Document
 * bytes stay identical across state updates.
 *
 * Dispatch on [HaEntity] instead of raw strings — exhaustive when.
 */
object LiveBindings {

    private fun name(entityId: String, suffix: String): String = "$entityId.$suffix"

    /**
     * A binary "is this entity active" signal. Returns null for
     * entities whose domain doesn't have an active/inactive concept
     * (pure sensors, weather) — the caller should then pick static
     * styling instead.
     */
    fun isOn(entity: EntityState?): RemoteBoolean? {
        val active = entity?.toTyped()?.isActive ?: return null
        return RemoteBoolean.createNamedRemoteBoolean(
            name(entity.entityId, "is_on"),
            active,
            RemoteState.Domain.User,
        )
    }

    /**
     * Entity's primary state string as a named [RemoteString] so the
     * player can update the text without re-encoding (e.g. "21.4 °C"
     * becoming "22.1 °C" when the sensor reports).
     */
    fun state(entity: EntityState?, formatted: String): RemoteString {
        val entityId = entity?.entityId ?: return formatted.rs
        return RemoteString.createNamedRemoteString(
            name(entityId, "state"),
            formatted,
            RemoteState.Domain.User,
        )
    }
}
