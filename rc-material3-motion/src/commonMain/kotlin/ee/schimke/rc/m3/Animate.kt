@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.remote.creation.compose.state.AnimatedRemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * [this], moving by [spec] whenever the document state behind it changes (a host writing a new
 * named value, a `select` flipping).
 *
 * The player starts settled — the first value it evaluates is both start and target — so only a
 * later change animates; a document played with current values never animates in. Don't feed this a
 * constant to make an entrance: that is an appearance animation, not an update.
 *
 * Returns [this] unchanged for [RcMotionSpec.Snap], and when [LocalRcAnimationsEnabled] is off. A
 * spring becomes its [RcMotionSpec.Spring.asTween] when [LocalRcSpringsSupported] is off.
 */
@Composable
fun RemoteFloat.animate(spec: RcMotionSpec): RemoteFloat {
  if (!LocalRcAnimationsEnabled.current) return this
  val played =
    if (spec is RcMotionSpec.Spring && !LocalRcSpringsSupported.current) spec.asTween() else spec
  val description = played.encode() ?: return this
  // `AnimatedRemoteFloat` is `@RememberInComposition` since remote-compose alpha21.
  return remember(this, played) { AnimatedRemoteFloat(this, description) }
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
