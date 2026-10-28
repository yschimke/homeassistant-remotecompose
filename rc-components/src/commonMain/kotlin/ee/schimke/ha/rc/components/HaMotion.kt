package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import ee.schimke.rc.m3.RcMotionScheme
import ee.schimke.rc.m3.RcMotionSpec
import ee.schimke.rc.m3.currentRcMotion

/**
 * The cards' motion schemes beyond the Material ones in `rc-material3-motion`. See
 * docs/design/animation-first-design-systems.md.
 */
object HaMotion {
  /**
   * The tweens the cards had before motion schemes: 0.20 s for a switch or a toggle's accent, 0.45
   * s for a gauge or a dial, on the standard curve. A theme not yet designed in motion keeps these,
   * so its documents encode exactly as they did.
   */
  val Classic =
    RcMotionScheme(
      spatialFast = RcMotionSpec.Tween(0.20f),
      spatial = RcMotionSpec.Tween(0.45f),
      spatialSlow = RcMotionSpec.Tween(0.60f),
      effectsFast = RcMotionSpec.Tween(0.20f),
      effects = RcMotionSpec.Tween(0.45f),
      effectsSlow = RcMotionSpec.Tween(0.60f),
    )
}

/** The active card style's motion scheme, under the host's motion mode (ambient, reduced). */
@Composable @ReadOnlyComposable fun haMotion(): RcMotionScheme = currentRcMotion()
