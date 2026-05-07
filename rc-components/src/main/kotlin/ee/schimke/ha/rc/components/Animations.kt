@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.core.operations.utilities.easing.FloatAnimation
import androidx.compose.remote.creation.compose.state.AnimatedRemoteFloat
import androidx.compose.remote.creation.compose.state.CUBIC_ACCELERATE
import androidx.compose.remote.creation.compose.state.CUBIC_ANTICIPATE
import androidx.compose.remote.creation.compose.state.CUBIC_DECELERATE
import androidx.compose.remote.creation.compose.state.CUBIC_LINEAR
import androidx.compose.remote.creation.compose.state.CUBIC_OVERSHOOT
import androidx.compose.remote.creation.compose.state.CUBIC_STANDARD
import androidx.compose.remote.creation.compose.state.EASE_OUT_BOUNCE
import androidx.compose.remote.creation.compose.state.EASE_OUT_ELASTIC
import androidx.compose.remote.creation.compose.state.RemoteFloat

/**
 * Easing curve identifiers (mirrored from RemoteCompose's `RemoteFloat.kt`).
 * The encoded `.rc` document carries the easing type id; the player
 * evaluates the curve at playback time.
 */
object RcEasing {
    const val Standard: Int = CUBIC_STANDARD
    const val Accelerate: Int = CUBIC_ACCELERATE
    const val Decelerate: Int = CUBIC_DECELERATE
    const val Linear: Int = CUBIC_LINEAR
    const val Anticipate: Int = CUBIC_ANTICIPATE
    const val Overshoot: Int = CUBIC_OVERSHOOT
    const val Bounce: Int = EASE_OUT_BOUNCE
    const val Elastic: Int = EASE_OUT_ELASTIC
}

/**
 * Wrap [input] in an [AnimatedRemoteFloat] so the player tweens between
 * successive values whenever the document state behind [input]
 * changes (typically via `RemoteBoolean.select(1f.rf, 0f.rf)` or a
 * `MutableRemoteFloat` write).
 *
 * The encoded animation spec is a 5-tuple
 * `(duration, easingType, customCurve, wrap, offset)` — we only ever
 * need the first two, so the rest stay at their defaults.
 *
 * @param durationSeconds tween duration in seconds (player real time).
 * @param easing easing curve id from [RcEasing].
 */
fun animateRemoteFloat(
    input: RemoteFloat,
    durationSeconds: Float = 0.20f,
    easing: Int = RcEasing.Standard,
): RemoteFloat = AnimatedRemoteFloat(
    input,
    // Pack only the duration + easing; pass NaN for wrap / offset so
    // the packed array omits those slots (`packToFloatArray` skips NaN
    // entries; passing 0f would inject zero values the player would
    // try to apply as a wrap-around / phase offset).
    FloatAnimation.packToFloatArray(durationSeconds, easing, null, Float.NaN, Float.NaN),
)
