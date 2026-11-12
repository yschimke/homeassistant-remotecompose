@file:Suppress("RestrictedApi", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.common.AnimatedFloatExpression
import ee.schimke.composeai.rccreation.common.RemoteContext
import ee.schimke.composeai.rccreation.common.TouchStop
import ee.schimke.composeai.rccreation.common.Utils
import ee.schimke.composeai.rccreation.compose.capture.RemoteComposeCreationState
import ee.schimke.composeai.rccreation.compose.layout.RemoteDrawScope
import ee.schimke.composeai.rccreation.compose.state.LazyRemoteString
import ee.schimke.composeai.rccreation.compose.state.MutableRemoteFloat
import ee.schimke.composeai.rccreation.compose.state.MutableRemoteString
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteStateCacheKey
import ee.schimke.composeai.rccreation.compose.state.RemoteString

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
 * Only Android's captures write them ([touchControlsSupported]): the drag plays in the AndroidX
 * View player. A desktop or web capture draws the controls read-only.
 */
internal expect val touchControlsSupported: Boolean

/**
 * An id reserved in one document before its op is written. One per value; it hands out the same id
 * for every capture state it is asked in, and a new one per document.
 */
internal class ReservedId(val debugName: String) {
  internal val ids: MutableMap<Any, Int> = HashMap()
}

/** A cache key unique to one reserved value, so two sliders never share an id. */
private class ReservedKey(private val slot: ReservedId) : RemoteStateCacheKey {
  override fun toDebugString(): String = "reserved:${slot.debugName}"
}

private fun ReservedId.id(state: RemoteComposeCreationState): Int =
  ids.getOrPut(state) { state.document.nextId() }

/** The float [slot] names: usable in any expression before [defineTouch] writes it. */
internal fun reservedFloat(slot: ReservedId): RemoteFloat =
  MutableRemoteFloat(ReservedKey(slot)) { state -> Utils.asNan(slot.id(state)) }

/** The string [slot] names: usable in an action's payload before [defineString] writes it. */
internal fun reservedString(slot: ReservedId): RemoteString =
  MutableRemoteString(
    null,
    ReservedKey(slot),
    object : LazyRemoteString {
      override fun reserveTextId(creationState: RemoteComposeCreationState): Int =
        slot.id(creationState)

      override fun computeRequiredCodePointSet(
        creationState: RemoteComposeCreationState,
      ): Set<String>? = null
    },
  )

/**
 * Write [slot]'s touch expression into this canvas: a value that starts at [initial] and moves by
 * [unitsPerPx] for each pixel a finger drags across, within [min]..[max], times [gate] (0 freezes
 * it: a locked control). [stopAtEnds] eases a released value to whichever end is nearer.
 */
internal fun RemoteDrawScope.defineTouch(
  slot: ReservedId,
  initial: Float,
  min: Float,
  max: Float,
  unitsPerPx: RemoteFloat,
  gate: RemoteFloat?,
  stopAtEnds: Boolean,
) {
  val state = remoteComposeCreationState
  val exp = buildList {
    add(RemoteContext.FLOAT_TOUCH_POS_X)
    add(unitsPerPx.getFloatIdForCreationState(state))
    add(AnimatedFloatExpression.MUL)
    if (gate != null) {
      add(gate.getFloatIdForCreationState(state))
      add(AnimatedFloatExpression.MUL)
    }
  }
  state.document.addTouchExpression(
    id = slot.id(state),
    value = initial,
    min = min,
    max = max,
    velocityId = 0f,
    touchEffects = 0,
    expression = exp.toFloatArray(),
    stopMode = if (stopAtEnds) TouchStop.ENDS else TouchStop.INSTANTLY,
  )
}

/** Write [value] into [slot]'s string from this canvas, so it follows [value] as it changes. */
internal fun RemoteDrawScope.defineString(slot: ReservedId, value: RemoteString) {
  val state = remoteComposeCreationState
  val document = state.document
  // A merge with the empty string copies [value] into the reserved id, and keeps it following.
  document.textMerge(slot.id(state), value.getIdForCreationState(state), document.addText(""))
}
