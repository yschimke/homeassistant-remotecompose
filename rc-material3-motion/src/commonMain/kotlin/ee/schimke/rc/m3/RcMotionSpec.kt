@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.remote.creation.compose.state.CUBIC_ACCELERATE
import androidx.compose.remote.creation.compose.state.CUBIC_ANTICIPATE
import androidx.compose.remote.creation.compose.state.CUBIC_DECELERATE
import androidx.compose.remote.creation.compose.state.CUBIC_LINEAR
import androidx.compose.remote.creation.compose.state.CUBIC_OVERSHOOT
import androidx.compose.remote.creation.compose.state.CUBIC_STANDARD
import androidx.compose.remote.creation.compose.state.EASE_OUT_BOUNCE
import androidx.compose.remote.creation.compose.state.EASE_OUT_ELASTIC
import kotlin.math.sqrt

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
 * Where a spring may not go. The player reflects the spring off `0` and/or `1` (position and
 * velocity), so a `0..1` fraction under [Unit] bounces off the ends of its scale instead of
 * overshooting past them.
 */
enum class RcBounds(internal val mask: Int) {
  None(0),
  /** Reflect off `0`. */
  Floor(1),
  /** Reflect off `1`. */
  Ceiling(2),
  /** Reflect off both ends of `0..1`. */
  Unit(3),
}

/** One motion token: how a value moves to each new target the document gives it. */
sealed interface RcMotionSpec {
  /**
   * A physical spring, written as Compose writes one ([stiffness], [dampingRatio], mass 1). A
   * spring keeps its velocity when its target changes mid-flight, so repeated updates glide.
   *
   * [stopThreshold] is in the animated value's units: the default suits a `0..1` fraction.
   */
  data class Spring(
    val stiffness: Float,
    val dampingRatio: Float,
    val bounds: RcBounds = RcBounds.None,
    val stopThreshold: Float = 0.001f,
  ) : RcMotionSpec {
    init {
      require(stiffness > 0f) { "stiffness must be positive" }
      require(dampingRatio > 0f) { "dampingRatio must be positive" }
      require(stopThreshold > 0f) { "stopThreshold must be positive" }
    }

    /** The damping coefficient the player integrates with: `c = 2ζ√(k·m)`, mass 1. */
    val dampingCoefficient: Float
      get() = 2f * dampingRatio * sqrt(stiffness)
  }

  /** A fixed-length tween along an [RcEasing] curve. */
  data class Tween(val seconds: Float, val easing: Int = RcEasing.Standard) : RcMotionSpec

  /** No motion: the value jumps. Discrete instruments, and reduced motion. */
  data object Snap : RcMotionSpec
}

/**
 * [this] packed as the player's float-animation description, or null for [RcMotionSpec.Snap].
 *
 * The player (`FloatExpression`) reads a description longer than four floats whose first is `0` as
 * a spring — `[0, stiffness, damping, stopThreshold, boundaryMode]` with the mode an int in float
 * bits — and anything else as a `FloatAnimation` tween: `[duration, easing]`, either trailing value
 * dropped when it is the default (1 s, standard).
 */
fun RcMotionSpec.encode(): FloatArray? =
  when (this) {
    is RcMotionSpec.Spring ->
      floatArrayOf(0f, stiffness, dampingCoefficient, stopThreshold, Float.fromBits(bounds.mask))
    is RcMotionSpec.Tween ->
      when {
        easing != RcEasing.Standard -> floatArrayOf(seconds, Float.fromBits(easing))
        seconds != 1f -> floatArrayOf(seconds)
        else -> floatArrayOf()
      }
    RcMotionSpec.Snap -> null
  }
