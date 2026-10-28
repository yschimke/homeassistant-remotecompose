package ee.schimke.rc.m3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Six motion tokens, in Material 3's shape: what moves × how far.
 *
 * - **Spatial** tokens move things — a fill, a needle, a thumb, a size — and may overshoot.
 * - **Effects** tokens change things in place — colour, alpha, a crossfade — and never overshoot: a
 *   colour past its target is a wrong colour.
 *
 * A reading's text takes an effects token or none: a number the entity never had must not show.
 *
 * Animate **fractions** (`0..1`) with these tokens, not raw values: a spring's stop threshold is in
 * the value's units, and the Material spatial springs reflect off `0` and `1` ([RcBounds.Unit]).
 */
data class RcMotionScheme(
  val spatialFast: RcMotionSpec,
  val spatial: RcMotionSpec,
  val spatialSlow: RcMotionSpec,
  val effectsFast: RcMotionSpec,
  val effects: RcMotionSpec,
  val effectsSlow: RcMotionSpec,
) {
  /** This scheme under [mode]: itself, [ambient], or with its spatial motion removed. */
  fun resolve(mode: RcMotionMode): RcMotionScheme =
    when (mode) {
      RcMotionMode.Normal -> this
      RcMotionMode.Ambient -> ambient()
      RcMotionMode.Reduced -> reducedMotion()
    }

  /**
   * Reduced motion: nothing travels (spatial tokens [RcMotionSpec.Snap]), short fades stay so a
   * change is still noticed.
   */
  fun reducedMotion(): RcMotionScheme =
    copy(
      spatialFast = RcMotionSpec.Snap,
      spatial = RcMotionSpec.Snap,
      spatialSlow = RcMotionSpec.Snap,
      effectsSlow = effects,
    )

  companion object {
    /** Effects springs are shared by both Material schemes: critically damped. */
    private val Effects =
      Triple(
        RcMotionSpec.Spring(stiffness = 3800f, dampingRatio = 1f),
        RcMotionSpec.Spring(stiffness = 1600f, dampingRatio = 1f),
        RcMotionSpec.Spring(stiffness = 800f, dampingRatio = 1f),
      )

    /**
     * Material 3 `MotionScheme.expressive()`: underdamped spatial springs with a visible bounce.
     * Spatial tokens are bounded to `0..1`, so a fraction bounces off the end of its track.
     */
    fun expressive(): RcMotionScheme =
      RcMotionScheme(
        spatialFast = RcMotionSpec.Spring(800f, 0.6f, RcBounds.Unit),
        spatial = RcMotionSpec.Spring(380f, 0.8f, RcBounds.Unit),
        spatialSlow = RcMotionSpec.Spring(200f, 0.8f, RcBounds.Unit),
        effectsFast = Effects.first,
        effects = Effects.second,
        effectsSlow = Effects.third,
      )

    /** Material 3 `MotionScheme.standard()`: barely-underdamped spatial springs. */
    fun standard(): RcMotionScheme =
      RcMotionScheme(
        spatialFast = RcMotionSpec.Spring(1400f, 0.9f, RcBounds.Unit),
        spatial = RcMotionSpec.Spring(700f, 0.9f, RcBounds.Unit),
        spatialSlow = RcMotionSpec.Spring(300f, 0.9f, RcBounds.Unit),
        effectsFast = Effects.first,
        effects = Effects.second,
        effectsSlow = Effects.third,
      )

    /**
     * Ambient: calm motion for always-on and glanceable displays (a kiosk, a watch in ambient, a
     * wall panel). Every spring is critically damped and slow — nothing bounces, nothing snaps —
     * settling (to 2 %) in about 0.35 / 0.6 / 0.9 s spatially and 0.4 / 0.65 / 1.05 s for effects.
     */
    fun ambient(): RcMotionScheme =
      RcMotionScheme(
        spatialFast = RcMotionSpec.Spring(300f, 1f),
        spatial = RcMotionSpec.Spring(100f, 1f),
        spatialSlow = RcMotionSpec.Spring(40f, 1f),
        effectsFast = RcMotionSpec.Spring(200f, 1f),
        effects = RcMotionSpec.Spring(80f, 1f),
        effectsSlow = RcMotionSpec.Spring(30f, 1f),
      )
  }
}

/** How the host wants things to move, independent of the design's own scheme. */
enum class RcMotionMode {
  /** The design's own scheme. */
  Normal,
  /** [RcMotionScheme.ambient], whatever the design: an always-on or glanceable display. */
  Ambient,
  /** [RcMotionScheme.reducedMotion] of the design's scheme: the user asked for less motion. */
  Reduced,
}

/** The design's motion scheme. Material 3 standard unless a design provides its own. */
val LocalRcMotionScheme = staticCompositionLocalOf { RcMotionScheme.standard() }

/** The host's motion mode, applied over [LocalRcMotionScheme]. */
val LocalRcMotionMode = staticCompositionLocalOf { RcMotionMode.Normal }

/**
 * Whether animations are encoded into the document at all. Deterministic captures (screenshot
 * tests, previews) turn this off so a document carries settled values and no animation for the
 * capture to catch mid-flight.
 */
val LocalRcAnimationsEnabled = staticCompositionLocalOf { true }

/** The motion scheme in effect: the design's, under the host's mode. */
@Composable
@ReadOnlyComposable
fun currentRcMotion(): RcMotionScheme =
  LocalRcMotionScheme.current.resolve(LocalRcMotionMode.current)
