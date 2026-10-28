@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString

/**
 * The writer calls behind a drag control: a slider ([RemoteHaSlider]) and the slide-to-unlock strip
 * ([RemoteHaControlLockFrame]).
 *
 * Remote Compose drives a dragged value with a `TouchExpression`: a float the player moves as a
 * finger drags, here in relative mode (the value moves by how far the finger went, so a touch never
 * makes it jump). Two rules of the AndroidX player shape this API, both checked by
 * `SliderPlaybackTest`:
 * - **The expression and the release live on one canvas.** The player runs a component's touch-up
 *   actions only for the component that took the touch-down, and the touch expression only reacts
 *   inside the component it is written in.
 * - **The touch value and the text made from it are defined in the canvas.** The release's payload
 *   is in the canvas's modifier, which is written before its content; text made there from a touch
 *   value the content defines later is formatted once and never again. So both ids are
 *   [reserved][ReservedId] where the modifier needs them, and their ops are written inside the
 *   canvas ([defineTouch], [defineString]).
 *
 * The Compose Multiplatform fork (desktop and web captures) has no touch-expression writer:
 * [touchControlsSupported] is false there and the controls draw read-only.
 */
internal expect val touchControlsSupported: Boolean

/**
 * An id reserved in one document before its op is written. One per value; it hands out the same id
 * for every capture state it is asked in, and a new one per document.
 */
internal class ReservedId(val debugName: String) {
  internal val ids: MutableMap<Any, Int> = HashMap()
}

/** The float [slot] names: usable in any expression before [defineTouch] writes it. */
internal expect fun reservedFloat(slot: ReservedId): RemoteFloat

/** The string [slot] names: usable in an action's payload before [defineString] writes it. */
internal expect fun reservedString(slot: ReservedId): RemoteString

/**
 * Write [slot]'s touch expression into this canvas: a value that starts at [initial] and moves by
 * [unitsPerPx] for each pixel a finger drags across, within [min]..[max], times [gate] (0 freezes
 * it: a locked control). [stopAtEnds] eases a released value to whichever end is nearer.
 */
internal expect fun RemoteDrawScope.defineTouch(
  slot: ReservedId,
  initial: Float,
  min: Float,
  max: Float,
  unitsPerPx: RemoteFloat,
  gate: RemoteFloat?,
  stopAtEnds: Boolean,
)

/** Write [value] into [slot]'s string from this canvas, so it follows [value] as it changes. */
internal expect fun RemoteDrawScope.defineString(slot: ReservedId, value: RemoteString)
