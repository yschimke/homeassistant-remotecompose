@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteImageBitmap
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The few writer calls that differ between AndroidX's `remote-creation-compose` (Android) and the
 * Compose Multiplatform fork of it (JVM). Both share AndroidX's package and creation API; what
 * differs is the writer behind [RemoteComposeCreationState]: AndroidX's `RemoteComposeWriter`, or
 * the fork's common `RemoteWriter`.
 */
/**
 * A bitmap named [name] in [domain], baked into the document from [bitmap]. The
 * `MutableRemoteImageBitmap` behind it is built per platform: its writer callback takes AndroidX's
 * `RemoteComposeCreationState` on Android and the fork's `RemoteComposeCreationContext` on the JVM.
 */
internal expect fun namedRemoteBitmap(
  name: String,
  domain: RemoteState.Domain,
  bitmap: ImageBitmap,
): RemoteImageBitmap

/** A URL bitmap named [name] in [domain], with real [width] and [height]. */
internal expect fun namedRemoteBitmapUrl(
  name: String,
  domain: RemoteState.Domain,
  url: String,
  width: Int,
  height: Int,
): RemoteImageBitmap

/** Whether the document's profile supports [operation], a Remote Compose opcode. */
internal expect fun RemoteComposeCreationState.supportsOperation(operation: Int): Boolean

/**
 * [this] as a whole number with at least [digits] digits, zero-padded (`DecimalFormat("00")` for
 * two). AndroidX formats with `java.text.DecimalFormat`, the fork with its own `DecimalFormat`.
 */
expect fun RemoteFloat.toPaddedRemoteString(digits: Int): RemoteString

/** [RemoteFloat.toPaddedRemoteString] for an integer. */
expect fun RemoteInt.toPaddedRemoteString(digits: Int): RemoteString

/** Remote Compose protocol values the player defines, which neither creation library exports. */
internal object RcProtocol {
  private fun asNan(id: Int): Float = Float.fromBits(id or -0x800000)

  /** The host's time: seconds, minutes and hours, as the player's system float variables. */
  val FLOAT_TIME_IN_SEC: Float = asNan(2)
  val FLOAT_TIME_IN_MIN: Float = asNan(3)
  val FLOAT_TIME_IN_HR: Float = asNan(4)

  /**
   * A float animation of [duration] seconds with [easing], packed as the player's `FloatAnimation`
   * reads it: no custom spec, initial value or wrap.
   */
  fun tween(duration: Float, easing: Int): FloatArray =
    when {
      easing != 1 -> floatArrayOf(duration, Float.fromBits(easing))
      duration != 1f -> floatArrayOf(duration)
      else -> floatArrayOf()
    }
}
