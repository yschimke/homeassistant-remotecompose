@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.selectIfGe

// Trinitron: Sony hi-fi instruments. A dial is a VU meter — a hairline scale, index ticks, a red
// peak zone and a swinging needle. A thermometer is an LED level column beside a tick scale, a bar
// an LED strip, a battery a hairline frame of LED cells. Lit cells past the peak zone glow red.

/** Where the red peak zone starts when the reading marks none. */
private const val PeakFrom = 0.8f

internal fun RemoteDrawScope.trinitronArc(
  look: MeterLook,
  fraction: RemoteFloat,
  baseline: Float,
  zones: HaMeterZones,
) {
  val arc = ArcBox(width, height, 4f, baseline)
  // The peak zone starts at the live mark when the card binds one, else at the static one.
  val peak: RemoteFloat =
    // A VU meter always has a peak zone: a live mark of 1 (no hot end) falls back to PeakFrom.
    zones.hotFromLive?.let {
      selectIfGe(it, 0.999f.rf, PeakFrom.rf, remoteMax(it, 0.05f.rf).min(1f.rf))
    } ?: (zones.hotFrom ?: PeakFrom).coerceIn(0.05f, 1f).rf
  // Scale: a hairline to the peak zone, then a heavier red band to the end.
  drawArc(
    strokePaint(2f, look.muted, round = false),
    180f.rf,
    peak * 180f.rf,
    false,
    arc.topLeft,
    arc.size,
  )
  drawArc(
    strokePaint(6f, look.hot, round = false),
    180f.rf + peak * 180f.rf,
    (1f.rf - peak) * 180f.rf,
    false,
    arc.topLeft,
    arc.size,
  )
  // Eleven index ticks inside the scale, every other one long.
  val tickPaint = strokePaint(2f, look.muted, round = false)
  for (i in 0..10) {
    val deg = i * 18f
    val len = arc.radius * (if (i % 2 == 0) 0.14f else 0.07f).rf
    drawLine(
      tickPaint,
      arc.point(deg, arc.radius - 3f.rf),
      arc.point(deg, arc.radius - 3f.rf - len),
    )
  }
  // The needle: drawn pointing at 0 (left) and swung clockwise by the live fraction.
  val pivot = RemoteOffset(arc.cx, arc.baseY)
  rotate(fraction * 180f.rf, pivot) {
    drawLine(
      strokePaint(3f, look.text),
      pivot,
      RemoteOffset(arc.cx - arc.radius * 0.92f.rf, arc.baseY),
    )
  }
  fillCircle(fillPaint(look.text), arc.cx, arc.baseY, arc.radius * 0.06f.rf)
}

/** A run of [count] LED cells along a main axis, lit from the start. */
private fun RemoteDrawScope.ledRun(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
  count: Int,
  vertical: Boolean,
  start: RemoteFloat,
  len: RemoteFloat,
  cross: RemoteFloat,
  thick: RemoteFloat,
) {
  val pitch = len / count.toFloat().rf
  val cell = pitch * 0.55f.rf
  for (i in 0 until count) {
    val color = look.segment(fraction, i, count, zones)
    if (vertical) {
      fillRect(color, cross, start + len - pitch * (i + 1).toFloat().rf, thick, cell)
    } else {
      fillRect(color, start + pitch * i.toFloat().rf, cross, cell, thick)
    }
  }
}

internal fun RemoteDrawScope.trinitronBar(
  look: MeterLook,
  fraction: RemoteFloat,
  vertical: Boolean,
  zones: HaMeterZones,
) {
  val peakZones =
    if (zones.hotFromLive != null) zones else zones.copy(hotFrom = zones.hotFrom ?: PeakFrom)
  if (vertical) ledRun(look, fraction, peakZones, 24, true, 0f.rf, height, 0f.rf, width)
  else ledRun(look, fraction, peakZones, 24, false, 0f.rf, width, 0f.rf, height)
}

/** An LED level column beside a five-tick scale, at 1 : 3. */
internal fun RemoteDrawScope.trinitronThermometer(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 1f / 3f)
  val scaleW = box.width * 0.3f.rf
  val columnX = box.left + box.width * 0.42f.rf
  val columnW = box.width * 0.58f.rf
  // The scale: a hairline spine and ticks at 0, 25, 50, 75, 100 %.
  val stroke = (box.width * 0.04f.rf).min(2f.rf)
  fillRect(look.muted, box.left + scaleW, box.top, stroke, box.height)
  for (i in 0..4) {
    val y = box.bottom - box.height * (i / 4f).rf
    val len = if (i % 2 == 0) scaleW else scaleW * 0.55f.rf
    fillRect(look.muted, box.left + scaleW - len, y - stroke / 2f.rf, len, stroke)
  }
  ledRun(
    look,
    fraction,
    if (zones.hotFromLive != null) zones else zones.copy(hotFrom = zones.hotFrom ?: PeakFrom),
    20,
    true,
    box.top,
    box.height,
    columnX,
    columnW,
  )
}

/** A hairline frame of ten LED cells, the low zone lit red. */
internal fun RemoteDrawScope.trinitronBattery(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 2.4f)
  val nubW = box.width * 0.05f.rf
  val bodyW = box.width - nubW
  val stroke = (box.height * 0.07f.rf).min(2f.rf)
  outlineRect(look.muted, box.left, box.top, bodyW, box.height, stroke)
  fillRect(
    look.muted,
    box.left + bodyW,
    box.top + box.height * 0.32f.rf,
    nubW,
    box.height * 0.36f.rf,
  )
  val inset = stroke * 2.5f.rf
  ledRun(
    look,
    fraction,
    zones,
    10,
    false,
    box.left + inset,
    bodyW - inset * 2f.rf,
    box.top + inset,
    box.height - inset * 2f.rf,
  )
}
