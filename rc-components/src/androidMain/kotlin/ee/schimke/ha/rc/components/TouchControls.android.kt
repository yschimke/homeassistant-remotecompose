@file:Suppress("RestrictedApi", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package ee.schimke.ha.rc.components

import androidx.compose.remote.core.operations.TouchExpression
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression
import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.state.LazyRemoteString
import androidx.compose.remote.creation.compose.state.MutableRemoteFloat
import androidx.compose.remote.creation.compose.state.MutableRemoteString
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteStateCacheKey
import androidx.compose.remote.creation.compose.state.RemoteString

internal actual val touchControlsSupported: Boolean = true

/** A cache key unique to one reserved value, so two sliders never share an id. */
private class ReservedKey(private val slot: ReservedId) : RemoteStateCacheKey {
  override fun toDebugString(): String = "reserved:${slot.debugName}"
}

private fun ReservedId.id(state: RemoteComposeCreationState): Int =
  ids.getOrPut(state) { state.document.nextId() }

internal actual fun reservedFloat(slot: ReservedId): RemoteFloat =
  MutableRemoteFloat(ReservedKey(slot)) { state -> Utils.asNan(slot.id(state)) }

internal actual fun reservedString(slot: ReservedId): RemoteString =
  MutableRemoteString(
    null,
    ReservedKey(slot),
    object : LazyRemoteString {
      override fun reserveTextId(creationState: RemoteComposeCreationState): Int =
        slot.id(creationState)

      override fun computeRequiredCodePointSet(
        creationState: RemoteComposeCreationState
      ): Set<String>? = null
    },
  )

internal actual fun RemoteDrawScope.defineTouch(
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
    add(Rc.Touch.POSITION_X)
    add(unitsPerPx.getFloatIdForCreationState(state))
    add(AnimatedFloatExpression.MUL)
    if (gate != null) {
      add(gate.getFloatIdForCreationState(state))
      add(AnimatedFloatExpression.MUL)
    }
  }
  state.document.buffer.addTouchExpression(
    slot.id(state),
    initial,
    min,
    max,
    /* velocityId= */ 0f,
    /* touchEffects= */ 0,
    exp.toFloatArray(),
    if (stopAtEnds) TouchExpression.STOP_ENDS else TouchExpression.STOP_INSTANTLY,
    null,
    null,
  )
}

internal actual fun RemoteDrawScope.defineString(slot: ReservedId, value: RemoteString) {
  val state = remoteComposeCreationState
  val document = state.document
  // A merge with the empty string copies [value] into the reserved id, and keeps it following.
  document.buffer.textMerge(
    slot.id(state),
    value.getIdForCreationState(state),
    document.addText(""),
  )
}
