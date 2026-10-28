package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import ee.schimke.rc.m3.RcBounds
import ee.schimke.rc.m3.RcMotionScheme
import ee.schimke.rc.m3.RcMotionSpec
import ee.schimke.rc.m3.resolveRcMotion

/**
 * The cards' motion schemes beyond the Material ones in `rc-material3-motion`. See
 * docs/design/animation-first-design-systems.md.
 */
object HaMotion {
  /**
   * A family's motion archetype (docs/design/animation-first-design-systems.md §6): designed
   * springs, physical ballistics or a discrete display. Classic keeps the tweens it always had.
   */
  fun forFamily(family: HaVisualFamily): RcMotionScheme =
    when (family) {
      HaVisualFamily.Classic -> Classic
      HaVisualFamily.Expressive -> RcMotionScheme.expressive()
      HaVisualFamily.Trinitron,
      HaVisualFamily.Braun,
      HaVisualFamily.Console -> Instrument
      HaVisualFamily.Lcd,
      HaVisualFamily.Lcars -> Lcd
      HaVisualFamily.Nixie -> Nixie
      HaVisualFamily.Cupertino -> Cupertino
      else -> RcMotionScheme.standard()
    }

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

  /**
   * **Physical ballistics** (Console, Trinitron, Braun): instruments with mass, tuned from the
   * standards they imitate rather than for delight.
   *
   * - `spatial` is the VU needle of ANSI C16.5: 99 % of a step in 300 ms with a 1–1.5 % overshoot,
   *   which is damping ratio ≈ 0.81 and stiffness ≈ 183. Gauges, dials and meters move by it.
   * - `spatialFast` is a key or rocker's mechanical throw: very stiff, one small rebound.
   * - `spatialSlow` is a heavy knob, a fader or a door: overdamped, no bounce at all.
   * - Effects are an incandescent pilot lamp, critically damped: `effectsFast` warms in about 0.2
   *   s, `effectsSlow` cools in about 0.6 s ([ee.schimke.rc.m3.animateAttackRelease]).
   */
  val Instrument =
    RcMotionScheme(
      spatialFast = RcMotionSpec.Spring(2000f, 0.55f, RcBounds.Unit),
      spatial = RcMotionSpec.Spring(183f, 0.81f, RcBounds.Unit),
      spatialSlow = RcMotionSpec.Spring(300f, 1.2f),
      effectsFast = RcMotionSpec.Spring(900f, 1f),
      effects = RcMotionSpec.Spring(400f, 1f),
      effectsSlow = RcMotionSpec.Spring(100f, 1f),
    )

  /**
   * **Discrete** displays (a segment LCD, Nixie tubes, LCARS panels): nothing glides between values
   * — a segment is lit or it isn't, so every spatial token snaps. What does move is the light
   * itself: a liquid-crystal segment darkens over [fadeSeconds], a Nixie cathode's glow overlaps
   * the next one's.
   */
  fun discrete(fadeSeconds: Float): RcMotionScheme =
    RcMotionScheme(
      spatialFast = RcMotionSpec.Snap,
      spatial = RcMotionSpec.Snap,
      spatialSlow = RcMotionSpec.Snap,
      effectsFast = RcMotionSpec.Tween(fadeSeconds, RcEasing.Linear),
      effects = RcMotionSpec.Tween(fadeSeconds, RcEasing.Linear),
      effectsSlow = RcMotionSpec.Tween(fadeSeconds * 2f, RcEasing.Linear),
    )

  /** A segment LCD: liquid crystal settles in about 150 ms. */
  val Lcd = discrete(0.15f)

  /** Nixie tubes: the old cathode's glow lingers a quarter second into the new one's. */
  val Nixie = discrete(0.25f)

  /**
   * **Cupertino**: iOS-style springs, critically damped and described by their response time
   * (`stiffness = (2π / response)²`): 0.3 s, 0.55 s (UIKit's default) and 0.8 s. Nothing bounces.
   */
  val Cupertino =
    RcMotionScheme(
      spatialFast = RcMotionSpec.Spring(439f, 1f),
      spatial = RcMotionSpec.Spring(130f, 1f),
      spatialSlow = RcMotionSpec.Spring(62f, 1f),
      effectsFast = RcMotionSpec.Spring(439f, 1f),
      effects = RcMotionSpec.Spring(130f, 1f),
      effectsSlow = RcMotionSpec.Spring(62f, 1f),
    )
}

/**
 * The active card style's motion scheme, under the host's motion mode (ambient) and the viewer's
 * reduce-motion preference. Read from the uber theme rather than `LocalRcMotionScheme`, whose
 * default is Material: a part played under a palette alone ([ProvideHaTheme]) keeps the Classic
 * tweens.
 */
@Composable
@ReadOnlyComposable
fun haMotion(): RcMotionScheme = resolveRcMotion(currentHaUberTheme().theme.motion)
