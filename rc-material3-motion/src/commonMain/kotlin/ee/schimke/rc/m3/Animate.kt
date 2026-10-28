@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.remote.creation.compose.state.AnimatedRemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteFloat
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
 * Returns [this] unchanged for [RcMotionSpec.Snap], and when [LocalRcAnimationsEnabled] is off.
 */
@Composable
fun RemoteFloat.animate(spec: RcMotionSpec): RemoteFloat {
  if (!LocalRcAnimationsEnabled.current) return this
  val description = spec.encode() ?: return this
  // `AnimatedRemoteFloat` is `@RememberInComposition` since remote-compose alpha21.
  return remember(this, spec) { AnimatedRemoteFloat(this, description) }
}
