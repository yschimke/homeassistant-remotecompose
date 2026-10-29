@file:Suppress("RestrictedApi")

package ee.schimke.rc.m3

import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
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
import androidx.compose.remote.creation.compose.state.clamp
import androidx.compose.remote.creation.compose.state.cos
import androidx.compose.remote.creation.compose.state.max
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
 * `1` pressed. Put [modifier] on the same component as its click or drag: its touch handlers run
 * beside that component's own, neither replacing nor delaying them.
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
  val up = valueChange(pressed, 0f.rf)
  val modifier =
    RemoteModifier.onTouchDown(valueChange(pressed, 1f.rf)).onTouchUp(up).onTouchCancel(up)
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
  val ambient = rcAmbient()
  RemoteBox(modifier = modifier, contentAlignment = RemoteAlignment.Center) {
    RemoteCanvas(modifier = RemoteModifier.fillMaxSize()) {
      val radius = min(width, height) * m3MorphCorner(selected, pressed)
      // Ambient: the container's outline instead of its fill.
      drawRoundRect(
        fill(color.copy(alpha = color.alpha * ambient.fade)),
        RemoteOffset(0f.rf, 0f.rf),
        RemoteSize(width, height),
        RemoteOffset(radius, radius),
      )
      val line = min(width, height) / 16f.rf
      drawRoundRect(
        stroke(color.copy(alpha = color.alpha * ambient.fraction), line),
        RemoteOffset(line / 2f.rf, line / 2f.rf),
        RemoteSize(width - line, height - line),
        RemoteOffset(radius - line / 2f.rf, radius - line / 2f.rf),
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

/** How thick a stroke or track is while ambient, as a fraction of its interactive thickness. */
private const val AmbientStroke = 0.35f

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
  val ambient = rcAmbient()
  RemoteCanvas(modifier = modifier) {
    // Ambient: a flat, thin line with no track; the wave comes to rest.
    val stroke = ambient.scale(height * 0.25f.rf, AmbientStroke)
    val amplitude = if (running) height * 0.2f.rf * ambient.fade else 0f.rf
    val mid = height / 2f.rf
    val start = stroke / 2f.rf
    val end = width - stroke / 2f.rf
    val head = start + (end - start) * progress
    val gap = stroke * 1.5f.rf
    // The flat track after the gap, and the stop dot at its end.
    drawLine(
      stroke(colors.track.copy(alpha = colors.track.alpha * ambient.fade), stroke),
      RemoteOffset(min(head + gap, end), mid),
      RemoteOffset(end, mid),
    )
    // The stop dot fades out as the head reaches it: there is nothing left to stop at.
    drawCircle(
      fill(
        colors.stop.copy(
          alpha = colors.stop.alpha * stopVisibility(end - head, gap * 2f.rf) * ambient.fade
        )
      ),
      stroke / 2f.rf,
      RemoteOffset(end, mid),
    )
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
  val ambient = rcAmbient()
  RemoteCanvas(modifier = modifier) {
    val size = min(width, height)
    // Ambient: a thin arc with no track; the ripple comes to rest. The radius keeps its interactive
    // value, so the arc doesn't shift as it thins.
    val fullStroke = size * strokeFraction.rf
    val stroke = ambient.scale(fullStroke, AmbientStroke)
    val fullAmplitude = if (running) fullStroke * 0.22f.rf else 0f.rf
    val amplitude = fullAmplitude * ambient.fade
    val radius = size / 2f.rf - fullStroke / 2f.rf - fullAmplitude
    val centre = RemoteOffset(width / 2f.rf, height / 2f.rf)
    drawCircle(
      stroke(colors.track.copy(alpha = colors.track.alpha * ambient.fade), stroke),
      radius,
      centre,
    )
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
  val ambient = rcAmbient()
  RemoteCanvas(modifier = modifier) {
    val size = min(width, height) * 0.7f.rf
    // A half turn a second; the corner breathes between round and square twice a turn.
    val morph = (sin(time * (2f * TwoPi).rf) + 1f.rf) / 2f.rf
    val radius = size * (M3RoundCorner.rf + (M3PressedCorner - M3RoundCorner).rf * morph)
    val centre = RemoteOffset(width / 2f.rf, height / 2f.rf)
    rotate(time * 180f.rf, centre) {
      // Ambient: the shape's outline instead of its fill.
      drawRoundRect(
        fill(color.copy(alpha = color.alpha * ambient.fade)),
        RemoteOffset(centre.x - size / 2f.rf, centre.y - size / 2f.rf),
        RemoteSize(size, size),
        RemoteOffset(radius, radius),
      )
      val line = size / 12f.rf
      drawRoundRect(
        stroke(color.copy(alpha = color.alpha * ambient.fraction), line),
        RemoteOffset(
          centre.x - size / 2f.rf + line / 2f.rf,
          centre.y - size / 2f.rf + line / 2f.rf,
        ),
        RemoteSize(size - line, size - line),
        RemoteOffset(radius - line / 2f.rf, radius - line / 2f.rf),
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

/**
 * How visible a stop dot is with [room] left between the head and it: fully over [fade] or more,
 * gone at none. The dot marks where the track ends, so it fades as the fill arrives there.
 */
private fun stopVisibility(room: RemoteFloat, fade: RemoteFloat): RemoteFloat =
  clamp(room / fade, 0f, 1f)

/**
 * Colours of an M3 Expressive slider: the active track, the inactive track, handle and stop. Give
 * the tracks opaque colours: each is drawn as a cap and a body that overlap by a pixel.
 */
data class RcM3SliderColors(
  val active: RemoteColor,
  val inactive: RemoteColor,
  val handle: RemoteColor = active,
  val stop: RemoteColor = active,
)

/** Where [drawM3Slider] put things, for a label that sits in one of the tracks. */
class RcM3SliderLayout
internal constructor(
  /** The x of the handle's centre. */
  val handle: RemoteFloat,
  /** The active track's end, before the gap. */
  val activeEnd: RemoteFloat,
  /** The inactive track's start, after the gap. */
  val inactiveStart: RemoteFloat,
  /** The track's height and its vertical centre. */
  val trackHeight: RemoteFloat,
  val middle: RemoteFloat,
)

/**
 * M3 Expressive's slider, drawn across this canvas at [fraction] (`0..1`): an active track, a gap,
 * a narrow handle the canvas's full height, a gap, and the inactive track ending in a stop dot. The
 * gaps travel with the handle and the stop dot fades as the active track reaches it. While
 * [pressed] (a `0..1` fraction, [rememberRcPress]) the handle narrows to half its width, springing
 * back on release. Draw it in the canvas that takes the drag, so the handle stays under the finger.
 *
 * While [ambient], the active track thins to a line and the inactive track and stop dot go: the
 * handle and the line still read as the value, with a fraction of the lit pixels.
 */
fun RemoteDrawScope.drawM3Slider(
  fraction: RemoteFloat,
  pressed: RemoteFloat,
  colors: RcM3SliderColors,
  ambient: RcAmbient = RcAmbient.Never,
): RcM3SliderLayout {
  val fullTrackHeight = height * 0.55f.rf
  val trackHeight = ambient.scale(fullTrackHeight, AmbientStroke)
  val top = (height - trackHeight) / 2f.rf
  val middle = height / 2f.rf
  val handleWidth = height * 0.1f.rf * (1f.rf - pressed * 0.5f.rf)
  val gap = height * 0.12f.rf
  // The handle's centre stays a half-handle and a gap in from either end, so a track always shows.
  val inset = height * 0.05f.rf + gap
  val handle = inset + (width - inset * 2f.rf) * clamp(fraction, 0f, 1f)
  val activeEnd = handle - handleWidth / 2f.rf - gap
  val inactiveStart = handle + handleWidth / 2f.rf + gap
  // Outer ends round, inner ends (beside the handle) square.
  trackSegment(0f.rf, activeEnd, top, trackHeight, colors.active, squareEnd = true)
  trackSegment(
    inactiveStart,
    width,
    top,
    trackHeight,
    colors.inactive.copy(alpha = colors.inactive.alpha * ambient.fade),
    squareEnd = false,
  )
  val stopRadius = fullTrackHeight * 0.12f.rf
  val stopX = width - fullTrackHeight / 2f.rf
  drawCircle(
    fill(
      colors.stop.copy(
        alpha =
          colors.stop.alpha * stopVisibility(stopX - inactiveStart, fullTrackHeight) * ambient.fade
      )
    ),
    stopRadius,
    RemoteOffset(stopX, middle),
  )
  drawRoundRect(
    fill(colors.handle),
    RemoteOffset(handle - handleWidth / 2f.rf, 0f.rf),
    RemoteSize(handleWidth, height),
    RemoteOffset(handleWidth / 2f.rf, handleWidth / 2f.rf),
  )
  return RcM3SliderLayout(handle, activeEnd, inactiveStart, trackHeight, middle)
}

/**
 * One track segment from [from] to [to], round at its outer end and square at the end beside the
 * handle ([squareEnd] true: the square end is [to]). Drawn as a half-disc cap and a rectangle that
 * meet without overlapping, so a translucent track colour is even all along.
 */
private fun RemoteDrawScope.trackSegment(
  from: RemoteFloat,
  to: RemoteFloat,
  top: RemoteFloat,
  height: RemoteFloat,
  color: RemoteColor,
  squareEnd: Boolean,
) {
  val length = max(to - from, 0f)
  val radius = min(height / 2f.rf, length)
  val paint = fill(color)
  // The body's edges are straight, so it needs no anti-aliasing, and it reaches under the cap's
  // anti-aliased diameter by a hair: otherwise the seam where they meet shows as a faint line.
  val body = RemotePaint {
    isAntiAlias = false
    style = PaintingStyle.Fill
  }
    .apply { this.color = color }
  val capSize = RemoteSize(radius * 2f.rf, height)
  if (squareEnd) {
    // Round start: the left half of a disc, then the body to the square end.
    drawArc(paint, 90f.rf, 180f.rf, true, RemoteOffset(from, top), capSize)
    val seam = min(radius, SeamPx.rf)
    drawRect(
      body,
      RemoteOffset(from + radius - seam, top),
      RemoteSize(length - radius + seam, height),
    )
  } else {
    // Square start, then the body, then the right half of a disc.
    val seam = min(radius, SeamPx.rf)
    drawRect(body, RemoteOffset(from, top), RemoteSize(length - radius + seam, height))
    drawArc(paint, (-90f).rf, 180f.rf, true, RemoteOffset(to - radius * 2f.rf, top), capSize)
  }
}

/** How far a track body reaches under its cap's edge, in px: past the cap's anti-aliasing. */
private const val SeamPx = 1f
