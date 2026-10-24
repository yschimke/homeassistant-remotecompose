@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf

// Material 3 Expressive: every meter is a fat capsule in the style of M3E's progress indicators —
// the value and the track are separate shapes with a gap between them, and a stop dot marks the
// end of the scale. No thermometer bulb, no battery outline: shape and gap carry the meaning.

private const val ExpressiveStrokeScale = 1.6f

internal fun RemoteDrawScope.expressiveArc(
  look: MeterLook,
  fraction: RemoteFloat,
  strokePx: Float,
  baseline: Float,
) {
  val stroke = strokePx * ExpressiveStrokeScale
  val arc = ArcBox(width, height, stroke / 2f + 1f, baseline)
  val sweep = fraction * 180f.rf
  val gapDeg = 9f.rf
  val trackStart = 180f.rf + sweep + gapDeg
  val trackSweep = remoteMax(180f.rf - sweep - gapDeg, 0f.rf)
  drawArc(strokePaint(stroke, look.track), trackStart, trackSweep, false, arc.topLeft, arc.size)
  fillCircle(fillPaint(look.value), arc.cx + arc.radius, arc.baseY, (stroke * 0.22f).rf)
  drawArc(strokePaint(stroke, look.value), 180f.rf, sweep, false, arc.topLeft, arc.size)
}

/**
 * A capsule track along [len] (from [start] on the main axis), [thick] across at [cross]: the value
 * capsule, a gap, the rest of the track, and a stop dot at the far end.
 */
private fun RemoteDrawScope.capsuleMeter(
  look: MeterLook,
  fraction: RemoteFloat,
  vertical: Boolean,
  start: RemoteFloat,
  len: RemoteFloat,
  cross: RemoteFloat,
  thick: RemoteFloat,
) {
  // The value is never shorter than a round dot, so a low reading reads as a dot, not a sliver.
  val filled = remoteMax(len * fraction, thick)
  val gap = thick * 0.45f.rf
  val trackFrom = filled + gap
  val trackLen = remoteMax(len - trackFrom, 0f.rf)
  val dotR = thick * 0.18f.rf
  if (vertical) {
    // Main axis runs bottom (start + len) to top (start).
    val bottom = start + len
    roundedBar(fillPaint(look.track), cross, start, thick, trackLen, vertical = true)
    roundedBar(fillPaint(look.value), cross, bottom - filled, thick, filled, vertical = true)
    fillCircle(fillPaint(look.value), cross + thick / 2f.rf, start + thick / 2f.rf, dotR)
  } else {
    roundedBar(fillPaint(look.track), start + trackFrom, cross, trackLen, thick, vertical = false)
    roundedBar(fillPaint(look.value), start, cross, filled, thick, vertical = false)
    fillCircle(fillPaint(look.value), start + len - thick / 2f.rf, cross + thick / 2f.rf, dotR)
  }
}

internal fun RemoteDrawScope.expressiveBar(
  look: MeterLook,
  fraction: RemoteFloat,
  vertical: Boolean,
) {
  if (vertical) capsuleMeter(look, fraction, true, 0f.rf, height, 0f.rf, width)
  else capsuleMeter(look, fraction, false, 0f.rf, width, 0f.rf, height)
}

/**
 * A thermometer as one fat vertical capsule, at 1 : 2.8 — no bulb, the gap and dot read the scale.
 */
internal fun RemoteDrawScope.expressiveThermometer(look: MeterLook, fraction: RemoteFloat) {
  val box = FitBox(width, height, 1f / 3.4f)
  val thick = box.width * 0.5f.rf
  capsuleMeter(look, fraction, true, box.top, box.height, box.centerX - thick / 2f.rf, thick)
}

/** A battery as a pill: the charge capsule, a gap, the track, and a rounded terminal. */
internal fun RemoteDrawScope.expressiveBattery(look: MeterLook, fraction: RemoteFloat) {
  val box = FitBox(width, height, 2.6f)
  val nubW = box.height * 0.22f.rf
  val bodyW = box.width - nubW * 1.6f.rf
  val thick = box.height * 0.6f.rf
  capsuleMeter(
    look,
    fraction,
    false,
    box.left,
    bodyW,
    box.top + (box.height - thick) / 2f.rf,
    thick,
  )
  fillRound(
    look.track,
    box.right - nubW,
    box.top + box.height * 0.3f.rf,
    nubW,
    box.height * 0.4f.rf,
    nubW / 2f.rf,
  )
}
