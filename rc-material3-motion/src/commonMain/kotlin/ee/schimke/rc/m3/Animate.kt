@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.runtime.Composable

/**
 * [this], moving by [spec] whenever the document state behind it changes (a host writing a new
 * named value, a `select` flipping).
 *
 * The player starts settled — the first value it evaluates is both start and target — so only a
 * later change animates; a document played with current values never animates in. Don't feed this a
 * constant to make an entrance: that is an appearance animation, not an update.
 *
 * While the host's display is ambient ([RcAmbientValue]) it shows [this] itself: an ambient host
 * draws a single frame a minute, and that frame must show the new value, not a spring's first step.
 * The spring still runs underneath, so leaving ambient picks up wherever it is.
 *
 * Returns [this] unchanged for [RcMotionSpec.Snap], and when [LocalRcAnimationsEnabled] is off. A
 * spring becomes its [RcMotionSpec.Spring.asTween] when [LocalRcSpringsSupported] is off.
 */
@Composable
fun RemoteFloat.animate(spec: RcMotionSpec): RemoteFloat {
  val animated = animateRaw(spec)
  if (animated === this) return this
  // The raw flag, not the animated [rcAmbient] fraction: one shared value rather than a second
  // animation per animated value. The host only sets it once animations have settled, so the
  // switch shows no jump.
  val ambient = rcAmbientFlag() ?: return animated
  return animated + (this - animated) * ambient
}

/**
 * [this] rising by [attack] and falling by [release]: a pilot lamp's filament warms fast and cools
 * slowly, a peak meter jumps up and decays. One animated value has one spec, so two copies follow
 * the same target and the larger is shown — on a rise the [attack] copy leads, on a fall the
 * [release] copy lags behind. Both should be critically damped: an overshoot would show through.
 */
@Composable
fun RemoteFloat.animateAttackRelease(attack: RcMotionSpec, release: RcMotionSpec): RemoteFloat {
  if (attack == release) return animate(attack)
  return max(animate(attack), animate(release))
}
