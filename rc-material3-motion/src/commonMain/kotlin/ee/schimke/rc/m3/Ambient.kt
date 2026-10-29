@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.remote.creation.compose.state.AnimatedRemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The host's ambient flag: a named float the host sets to `1` while its display is in a low-power
 * ambient state (a watch face's always-on, a display offloaded to a co-processor), `0` otherwise.
 *
 * It is a runtime value, not a capture setting: the same document plays interactive and ambient,
 * and the host flips it without a re-encode. While it is set, a document following the motion layer
 * draws **one truthful frame at a time**, because an ambient host draws rarely (about once a
 * minute) and may keep only a snapshot:
 * - every value animated by [animate] shows its target, so a frame drawn just after a change shows
 *   the new value, not the start of a spring that will never be drawn;
 * - loops on the player's clock (the wavy indicators, the loading indicator) come to rest;
 * - building blocks take their ambient look: outlines for fills, thin tracks, fewer lit pixels.
 *
 * A document that declares this name has told the host it handles ambient itself. One that doesn't
 * leaves the host to cope (dim it, or draw a placeholder). See `docs/architecture/ambient-mode.md`
 * for the host's side: settle, set, snapshot, tick.
 */
const val RcAmbientValue = "host.ambient"

/**
 * Whether captures read the host's ambient flag ([RcAmbientValue]). On by default. A host that
 * never goes ambient (a launcher widget's system renderer, a TV row) may turn it off to keep the
 * flag and its expressions out of the document.
 */
val LocalRcAmbientAware = staticCompositionLocalOf { true }

/**
 * The ambient flag's value until the host writes one: `false` (interactive) unless the capture is
 * for a host already ambient, or a preview of the ambient look. A host's write replaces it.
 */
val LocalRcAmbientInitial = staticCompositionLocalOf { false }

/**
 * The display's ambient state, as a fraction: `0` interactive, `1` ambient. Going ambient it jumps
 * to `1`, so the first frame drawn after the host sets its flag is already the ambient one (an
 * ambient host may draw no other). Coming back it fades to `0` on the scheme's `effectsFast`, so
 * the interactive look returns in a short fade.
 *
 * Read it with [color] and [fade] to draw the ambient look, or multiply a loop's amplitude by `1 -
 * fraction` to bring it to rest.
 */
class RcAmbient internal constructor(val fraction: RemoteFloat) {
  /** [interactive] while interactive, [ambient] while ambient. */
  fun color(interactive: RemoteColor, ambient: RemoteColor): RemoteColor =
    tween(interactive, ambient, fraction)

  /** `1` while interactive, `0` while ambient: the alpha of something ambient leaves out. */
  val fade: RemoteFloat
    get() = 1f.rf - fraction

  /**
   * [interactive] while interactive, reduced toward [ambientScale] of it while ambient: a track
   * that thins to a line.
   */
  fun scale(interactive: RemoteFloat, ambientScale: Float): RemoteFloat =
    interactive * (1f.rf - fraction * (1f - ambientScale).rf)

  companion object {
    /** Never ambient: the document doesn't read the flag. */
    val Never = RcAmbient(0f.rf)
  }
}

/**
 * The host's ambient state for this document ([RcAmbientValue]). Every call shares the one named
 * value, so a document declares it once however many parts read it. [RcAmbient.Never] when
 * [LocalRcAmbientAware] is off.
 */
@Composable
fun rcAmbient(): RcAmbient {
  val flag = rcAmbientFlag() ?: return RcAmbient.Never
  val motion = currentRcMotion()
  // The larger of the flag and its animation: a rise shows the flag at once, a fall the fade.
  return RcAmbient(max(flag, flag.animateRaw(motion.effectsFast)))
}

/** The host's flag, raw: `0` or `1`. Null when the document doesn't read it. */
@Composable
internal fun rcAmbientFlag(): RemoteFloat? {
  if (!LocalRcAmbientAware.current) return null
  val initial = if (LocalRcAmbientInitial.current) 1f else 0f
  return remember(initial) {
    RemoteFloat.createNamedRemoteFloat(RcAmbientValue, initial, RemoteState.Domain.User)
  }
}

/**
 * [this] animated by [spec] with no ambient handling: for the ambient fraction itself, which
 * [animate] would otherwise mix with itself.
 */
@Composable
internal fun RemoteFloat.animateRaw(spec: RcMotionSpec): RemoteFloat {
  if (!LocalRcAnimationsEnabled.current) return this
  val played =
    if (spec is RcMotionSpec.Spring && !LocalRcSpringsSupported.current) spec.asTween() else spec
  val description = played.encode() ?: return this
  return remember(this, played) { AnimatedRemoteFloat(this, description) }
}
