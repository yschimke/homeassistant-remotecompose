@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.remote.core.operations.utilities.easing.FloatAnimation
import androidx.compose.remote.core.operations.utilities.easing.SpringStopEngine
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The encoded specs, played by the player's own engines rather than a model of them. */
class RcMotionSpecTest {
  /** A step from 0 to 1 at 120 Hz for [seconds]: the spring's positions. */
  private fun step(spec: RcMotionSpec.Spring, seconds: Float = 2f): List<Float> {
    val engine = SpringStopEngine(spec.encode()!!)
    engine.setInitialValue(0f)
    engine.setTargetValue(1f)
    return (1..(seconds * 120).toInt()).map { engine.get(it / 120f) }
  }

  @Test
  fun springEncodesAsThePlayersSpringDescription() {
    val spec = RcMotionSpec.Spring(stiffness = 400f, dampingRatio = 0.5f, bounds = RcBounds.Unit)
    val encoded = spec.encode()!!
    // FloatExpression takes a spring for a description longer than four floats starting with 0.
    assertTrue(encoded.size > 4)
    assertEquals(0f, encoded[0])
    assertEquals(400f, encoded[1])
    assertEquals(2f * 0.5f * 20f, encoded[2])
    assertEquals(3, encoded[4].toRawBits())
  }

  @Test
  fun dampingRatioMatchesTheUnderdampedOvershoot() {
    val spec = RcMotionSpec.Spring(stiffness = 800f, dampingRatio = 0.6f)
    val expected = exp(-Math.PI * 0.6 / sqrt(1 - 0.36)).toFloat()
    val overshoot = step(spec).max() - 1f
    assertTrue(abs(overshoot - expected) < 0.01f, "overshoot $overshoot, expected $expected")
  }

  @Test
  fun criticallyDampedSpringsNeverOvershoot() {
    for (spec in listOf(RcMotionScheme.expressive(), RcMotionScheme.ambient())) {
      for (token in listOf(spec.effectsFast, spec.effects, spec.effectsSlow)) {
        val positions = step(token as RcMotionSpec.Spring)
        assertTrue(positions.max() <= 1.0001f, "$token overshot to ${positions.max()}")
        assertTrue(abs(positions.last() - 1f) < 0.01f, "$token did not settle")
      }
    }
  }

  @Test
  fun unitBoundsKeepAnExpressiveFractionOnItsTrack() {
    val positions = step(RcMotionScheme.expressive().spatialFast as RcMotionSpec.Spring)
    assertTrue(positions.all { it in 0f..1f }, "left 0..1: ${positions.min()}..${positions.max()}")
    assertTrue(abs(positions.last() - 1f) < 0.01f)
  }

  @Test
  fun ambientIsSlowerThanExpressive() {
    fun settle(spec: RcMotionSpec) =
      step(spec as RcMotionSpec.Spring).indexOfFirst { abs(it - 1f) < 0.02f }
    val ambient = RcMotionScheme.ambient()
    val expressive = RcMotionScheme.expressive()
    assertTrue(settle(ambient.spatial) > settle(expressive.spatial))
    assertTrue(settle(ambient.effects) > settle(expressive.effects))
  }

  @Test
  fun tweenEncodesAsFloatAnimation() {
    val encoded = RcMotionSpec.Tween(0.45f, RcEasing.Decelerate).encode()!!
    val animation = FloatAnimation(*encoded)
    assertEquals(0.45f, animation.duration)
    assertContentEquals(floatArrayOf(0.2f), RcMotionSpec.Tween(0.2f).encode())
  }

  @Test
  fun reducedMotionSnapsSpatialAndKeepsFades() {
    val reduced = RcMotionScheme.expressive().resolve(RcMotionMode.Reduced)
    assertNull(reduced.spatialFast.encode())
    assertNull(reduced.spatial.encode())
    assertEquals(RcMotionScheme.expressive().effects, reduced.effects)
    assertEquals(RcMotionScheme.ambient(), RcMotionScheme.standard().resolve(RcMotionMode.Ambient))
  }

  @Test
  fun attackReleaseRisesFastAndFallsSlowly() {
    fun lamp(from: Float, to: Float): Int {
      // Fresh engines: each keeps the time it last ran to.
      val attack = SpringStopEngine(RcMotionSpec.Spring(900f, 1f).encode()!!)
      val release = SpringStopEngine(RcMotionSpec.Spring(100f, 1f).encode()!!)
      for (engine in listOf(attack, release)) {
        engine.setInitialValue(from)
        engine.setTargetValue(to)
      }
      // The shown value is the larger copy; count frames until it is within 2 % of the target.
      return (1..240).indexOfFirst {
        val t = it / 120f
        abs(maxOf(attack.get(t), release.get(t)) - to) < 0.02f
      }
    }
    val warm = lamp(0f, 1f)
    val cool = lamp(1f, 0f)
    assertTrue(warm in 0..30, "warms in ${warm}f")
    assertTrue(cool > 2 * warm, "cools ($cool f) slower than it warms ($warm f)")
  }

  @Test
  fun springsStandInAsTweensOfAboutTheirLength() {
    val vu = RcMotionSpec.Spring(183f, 0.81f).asTween()
    assertTrue(vu.seconds in 0.3f..0.4f, "VU needle tween ${vu.seconds}s")
    assertEquals(RcEasing.Decelerate, vu.easing)
    assertEquals(RcEasing.Overshoot, RcMotionSpec.Spring(800f, 0.6f).asTween().easing)
    val slow = RcMotionScheme.ambient().effectsSlow as RcMotionSpec.Spring
    assertTrue(slow.asTween().seconds in 1f..1.1f)
  }
}
