@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.onTouchCancel
import androidx.compose.remote.creation.compose.modifier.onTouchDown
import androidx.compose.remote.creation.compose.modifier.onTouchUp
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteTimeVariables
import androidx.compose.remote.creation.compose.state.cos
import androidx.compose.remote.creation.compose.state.min
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteFloat
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.sin
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap

// Material 3 Expressive building blocks beyond the switch (RemoteM3Switch.kt): press feedback, the
// shape morph of a selected container, the wavy progress indicator and the loading indicator. Each
// draws from bound fractions and the motion scheme, and owns no action.

/**
 * A press, held in the document: [modifier] marks the component touched (touch down sets it, touch
 * up or cancel clears it) and [fraction] follows it on the scheme's `spatialFast`, `0` released to
 * `1` pressed. Put [modifier] on the same component as its click; the press neither replaces nor
 * delays the click.
 */
class RcPress internal constructor(val modifier: RemoteModifier, val fraction: RemoteFloat) {
  companion object {
    /** No press feedback: nothing marks the component, and the fraction stays `0`. */
    val None = RcPress(RemoteModifier, 0f.rf)
  }
}

/** A press for one component; see [RcPress]. */
@Composable
fun rememberRcPress(motion: RcMotionScheme = currentRcMotion()): RcPress {
  if (!LocalRcInteractivePlayer.current) return RcPress.None
  val pressed = rememberMutableRemoteFloat(0f)
  val modifier =
    RemoteModifier.onTouchDown(valueChange(pressed, 1f.rf))
      .onTouchUp(valueChange(pressed, 0f.rf))
      .onTouchCancel(valueChange(pressed, 0f.rf))
  return RcPress(modifier, pressed.animate(motion.spatialFast))
}

/**
 * The corner of an M3 Expressive container, as a fraction of its smaller side: fully round at rest,
 * squarer when [selected], squarer still while [pressed]. Toggle buttons and icon containers morph
 * this way; both inputs are `0..1` fractions, already animated.
 */
fun m3MorphCorner(selected: RemoteFloat, pressed: RemoteFloat): RemoteFloat {
  val resting = M3RoundCorner.rf + (M3SelectedCorner - M3RoundCorner).rf * selected
  return resting + (M3PressedCorner.rf - resting) * pressed
}

private const val M3RoundCorner = 0.5f
private const val M3SelectedCorner = 0.3f
private const val M3PressedCorner = 0.18f

/**
 * A container that morphs with its state ([m3MorphCorner]): round at rest, squarer when [selected],
 * squarer still while [pressed]. Draws [color] behind [content], centred.
 */
@Composable
@RemoteComposable
fun RemoteM3ShapeMorph(
  selected: RemoteFloat,
  pressed: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  RemoteBox(modifier = modifier, contentAlignment = RemoteAlignment.Center) {
    RemoteCanvas(modifier = RemoteModifier.fillMaxSize()) {
      val radius = min(width, height) * m3MorphCorner(selected, pressed)
      drawRoundRect(
        fill(color),
        RemoteOffset(0f.rf, 0f.rf),
        RemoteSize(width, height),
        RemoteOffset(radius, radius),
      )
    }
    content()
  }
}

/** Colours of a wavy progress indicator: the indicator, its track and the stop dot. */
data class RcM3ProgressColors(
  val indicator: RemoteColor,
  val track: RemoteColor,
  val stop: RemoteColor = indicator,
)

/**
 * The phase of a running wave, in radians: the player's animation clock while [running], else
 * still. A capture with animations off ([LocalRcAnimationsEnabled]) is still too, so a frozen
 * preview is deterministic.
 */
@Composable
private fun wavePhase(running: Boolean, cyclesPerSecond: Float): RemoteFloat =
  if (running && rcLoopsAllowed()) RemoteTimeVariables.animationTime * (cyclesPerSecond * TwoPi).rf
  else 0f.rf

private const val TwoPi = 6.2831855f

/**
 * Whether a loop on the player's clock may run: animations on, an interactive player, and the
 * viewer and host not asking for less motion (reduce motion, or the reduced or ambient mode, which
 * keep only short fades and slow springs). Otherwise a loop draws its resting frame.
 */
@Composable
private fun rcLoopsAllowed(): Boolean =
  LocalRcAnimationsEnabled.current &&
    LocalRcInteractivePlayer.current &&
    !LocalRcReduceMotion.current &&
    LocalRcMotionMode.current == RcMotionMode.Normal

/**
 * M3 Expressive's linear wavy progress indicator. The filled part waves while [running]; the rest
 * is a flat track after a gap, ending in a stop dot. [progress] is a `0..1` fraction (animate it on
 * a spatial token for live updates). The wave's motion is a loop on the player's clock, so pass
 * [running] only while the thing it shows is actually working: never as an entrance.
 */
@Composable
@RemoteComposable
fun RemoteM3WavyProgress(
  progress: RemoteFloat,
  running: Boolean,
  colors: RcM3ProgressColors,
  modifier: RemoteModifier = RemoteModifier,
) {
  val phase = wavePhase(running, cyclesPerSecond = 1f)
  RemoteCanvas(modifier = modifier) {
    val stroke = height * 0.25f.rf
    val amplitude = if (running) height * 0.2f.rf else 0f.rf
    val mid = height / 2f.rf
    val start = stroke / 2f.rf
    val end = width - stroke / 2f.rf
    val head = start + (end - start) * progress
    val gap = stroke * 1.5f.rf
    // The flat track after the gap, and the stop dot at its end.
    drawLine(
      stroke(colors.track, stroke),
      RemoteOffset(min(head + gap, end), mid),
      RemoteOffset(end, mid),
    )
    drawCircle(fill(colors.stop), stroke / 2f.rf, RemoteOffset(end, mid))
    // The wave: short segments along a sine, each clamped to the head so the rest collapse.
    val wavelength = height * 2.5f.rf
    val segments = WaveSegments
    val paint = stroke(colors.indicator, stroke)
    for (i in 0 until segments) {
      val x0 = min(start + (end - start) * (i.toFloat() / segments).rf, head)
      val x1 = min(start + (end - start) * ((i + 1).toFloat() / segments).rf, head)
      drawLine(
        paint,
        wavePoint(x0, mid, amplitude, wavelength, phase),
        wavePoint(x1, mid, amplitude, wavelength, phase),
      )
    }
  }
}

private fun wavePoint(
  x: RemoteFloat,
  mid: RemoteFloat,
  amplitude: RemoteFloat,
  wavelength: RemoteFloat,
  phase: RemoteFloat,
): RemoteOffset = RemoteOffset(x, mid + amplitude * sin(x / wavelength * TwoPi.rf - phase))

private const val WaveSegments = 48

/**
 * M3 Expressive's circular wavy progress indicator: an arc from 12 o'clock over [progress] of the
 * circle whose radius ripples while [running], then a flat track. As [RemoteM3WavyProgress], the
 * ripple is a loop on the player's clock: only while the thing it shows is working.
 */
@Composable
@RemoteComposable
fun RemoteM3WavyProgressRing(
  progress: RemoteFloat,
  running: Boolean,
  colors: RcM3ProgressColors,
  modifier: RemoteModifier = RemoteModifier,
  strokeFraction: Float = 0.12f,
) {
  val phase = wavePhase(running, cyclesPerSecond = 0.5f)
  RemoteCanvas(modifier = modifier) {
    val size = min(width, height)
    val stroke = size * strokeFraction.rf
    val amplitude = if (running) stroke * 0.22f.rf else 0f.rf
    val radius = size / 2f.rf - stroke / 2f.rf - amplitude
    val centre = RemoteOffset(width / 2f.rf, height / 2f.rf)
    drawCircle(stroke(colors.track, stroke), radius, centre)
    val paint = stroke(colors.indicator, stroke)
    val sweep = progress * TwoPi.rf
    for (i in 0 until RingSegments) {
      val a0 = min((i.toFloat() / RingSegments * TwoPi).rf, sweep)
      val a1 = min(((i + 1).toFloat() / RingSegments * TwoPi).rf, sweep)
      drawLine(
        paint,
        ringPoint(centre, radius, amplitude, a0, phase),
        ringPoint(centre, radius, amplitude, a1, phase),
      )
    }
  }
}

/**
 * A point at angle [a] from 12 o'clock, clockwise, on a radius rippling [RingWaves] times a turn.
 */
private fun ringPoint(
  centre: RemoteOffset,
  radius: RemoteFloat,
  amplitude: RemoteFloat,
  a: RemoteFloat,
  phase: RemoteFloat,
): RemoteOffset {
  val r = radius + amplitude * sin(a * RingWaves.rf - phase)
  return RemoteOffset(centre.x + r * sin(a), centre.y - r * cos(a))
}

private const val RingSegments = 120
private const val RingWaves = 8f

/**
 * M3 Expressive's loading indicator, for content that is on its way: a shape that turns and morphs
 * between a circle and a rounded square on the player's clock. (Material's own cycles through seven
 * polygons; this keeps to what a rounded rectangle can draw.) It loops, so show it only while
 * something is loading, and never in place of content the document already has.
 */
@Composable
@RemoteComposable
fun RemoteM3LoadingIndicator(color: RemoteColor, modifier: RemoteModifier = RemoteModifier) {
  val time = if (rcLoopsAllowed()) RemoteTimeVariables.animationTime else 0f.rf
  RemoteCanvas(modifier = modifier) {
    val size = min(width, height) * 0.7f.rf
    // A half turn a second; the corner breathes between round and square twice a turn.
    val morph = (sin(time * (2f * TwoPi).rf) + 1f.rf) / 2f.rf
    val radius = size * (M3RoundCorner.rf + (M3PressedCorner - M3RoundCorner).rf * morph)
    val centre = RemoteOffset(width / 2f.rf, height / 2f.rf)
    rotate(time * 180f.rf, centre) {
      drawRoundRect(
        fill(color),
        RemoteOffset(centre.x - size / 2f.rf, centre.y - size / 2f.rf),
        RemoteSize(size, size),
        RemoteOffset(radius, radius),
      )
    }
  }
}

private fun fill(color: RemoteColor) = RemotePaint {
  isAntiAlias = true
  style = PaintingStyle.Fill
}
  .apply { this.color = color }

private fun stroke(color: RemoteColor, width: RemoteFloat) = RemotePaint {
  isAntiAlias = true
  style = PaintingStyle.Stroke
  strokeWidth = width
  strokeCap = StrokeCap.Round
}
  .apply { this.color = color }
