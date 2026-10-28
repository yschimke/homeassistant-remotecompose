@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.runtime.Composable
import ee.schimke.rc.m3.LocalRcAnimationsEnabled
import ee.schimke.rc.m3.RcMotionSpec
import ee.schimke.rc.m3.animate

/** Easing curve identifiers; they live with the motion layer in `rc-material3-motion`. */
typealias RcEasing = ee.schimke.rc.m3.RcEasing

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
val LocalRemoteAnimationsEnabled = LocalRcAnimationsEnabled

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
): RemoteFloat = animateRemoteFloat(input, RcMotionSpec.Tween(durationSeconds, easing))

/**
 * [input], moving by [spec] — a token of the active card style's motion scheme ([haMotion]).
 *
 * As [animateRemoteFloat] by duration: nothing is encoded for a frozen capture, nor for a community
 * widget. Launcher widgets are re-baked on every change and the platform player's spring support is
 * unverified, so a widget capture encodes a spring token as no animation.
 */
@Composable
fun animateRemoteFloat(input: RemoteFloat, spec: RcMotionSpec): RemoteFloat {
  if (!LocalRemoteAnimationsEnabled.current) return input
  if (isWidgetActionCapture() && (visualFamily().isCommunity || spec is RcMotionSpec.Spring)) {
    return input
  }
  return input.animate(spec)
}

/**
 * [input] rising by [attack] and falling by [release] (`animateAttackRelease` in
 * `rc-material3-motion`), under the same capture rules as [animateRemoteFloat].
 */
@Composable
fun animateAttackRelease(
  input: RemoteFloat,
  attack: RcMotionSpec,
  release: RcMotionSpec,
): RemoteFloat {
  val rising = animateRemoteFloat(input, attack)
  val falling = animateRemoteFloat(input, release)
  return if (rising === falling || attack == release) rising else max(rising, falling)
}
