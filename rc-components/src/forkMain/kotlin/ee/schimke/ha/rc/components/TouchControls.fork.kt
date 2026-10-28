@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString

// The fork's writer has no touch expression: every drag control draws read-only here, so none of
// the calls below is ever reached (callers check [touchControlsSupported] first).
internal actual val touchControlsSupported: Boolean = false

internal actual fun reservedFloat(slot: ReservedId): RemoteFloat =
  error("No touch controls on this platform")

internal actual fun reservedString(slot: ReservedId): RemoteString =
  error("No touch controls on this platform")

internal actual fun RemoteDrawScope.defineTouch(
  slot: ReservedId,
  initial: Float,
  min: Float,
  max: Float,
  unitsPerPx: RemoteFloat,
  gate: RemoteFloat?,
  stopAtEnds: Boolean,
) {
  error("No touch controls on this platform")
}

internal actual fun RemoteDrawScope.defineString(slot: ReservedId, value: RemoteString) {
  error("No touch controls on this platform")
}
