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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Easing curve identifiers (mirrored from RemoteCompose's `RemoteFloat.kt`). The encoded `.rc`
 * document carries the easing type id; the player evaluates the curve at playback time.
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
 * Whether encoded value tweens ([animateRemoteFloat]) are emitted into the document.
 *
 * Production leaves this `true`: a card animates host writebacks (a toggle flip, a fresh gauge
 * value) so the player crossfades between successive values instead of snapping. Deterministic
 * screenshot renders flip it to `false` — bridged from `LocalHaClock.isFrozen` by the converter
 * host `ProvideCardRegistry` — so the encoded document carries the settled value with **no**
 * animation. A document otherwise kicks off its ~0.20 s startup tween (default → bound target) on
 * load, and the preview capture lands at a nondeterministic point in that tween, so the rendered
 * PNG drifts between runs (see the homeassistant-remotecompose #409 / #412 flaky-render
 * investigation). Same rationale as the clock card's frozen static-label path.
 */
val LocalRemoteAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * Wrap [input] in an [AnimatedRemoteFloat] so the player tweens between successive values whenever
 * the document state behind [input] changes (typically via `RemoteBoolean.select(1f.rf, 0f.rf)` or
 * a `MutableRemoteFloat` write).
 *
 * The encoded animation spec is a 5-tuple `(duration, easingType, customCurve, wrap, offset)` — we
 * only ever need the first two, so the rest stay at their defaults.
 *
 * When [LocalRemoteAnimationsEnabled] is `false` (a frozen preview / test render) the tween is
 * omitted entirely: [input] is returned as-is so the document encodes the settled value with no
 * startup animation for the capture to catch mid-flight.
 *
 * @param durationSeconds tween duration in seconds (player real time).
 * @param easing easing curve id from [RcEasing].
 */
@Composable
fun animateRemoteFloat(
  input: RemoteFloat,
  durationSeconds: Float = 0.20f,
  easing: Int = RcEasing.Standard,
): RemoteFloat {
  if (!LocalRemoteAnimationsEnabled.current) return input
  return AnimatedRemoteFloat(
    input,
    // Pack only the duration + easing; pass NaN for wrap / offset so
    // the packed array omits those slots (`packToFloatArray` skips NaN
    // entries; passing 0f would inject zero values the player would
    // try to apply as a wrap-around / phase offset).
    FloatAnimation.packToFloatArray(durationSeconds, easing, null, Float.NaN, Float.NaN),
  )
}
