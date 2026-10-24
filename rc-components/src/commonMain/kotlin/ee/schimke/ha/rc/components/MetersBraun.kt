@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf

// Braun: Dieter Rams's instruments. Nothing is filled. A dial is a hairline half circle with index
// ticks and a single orange pointer on a black hub, like a Braun clock or meter. A thermometer and
// a bar are tuning scales — a hairline spine with ticks and an orange pointer at the reading, the
// way a Braun radio shows its station. Only the battery keeps a fill, in its semantic colour.

private const val Hairline = 2f

internal fun RemoteDrawScope.braunArc(look: MeterLook, fraction: RemoteFloat, baseline: Float) {
  val arc = ArcBox(width, height, 3f, baseline)
  drawArc(strokePaint(Hairline, look.muted), 180f.rf, 180f.rf, false, arc.topLeft, arc.size)
  val tick = strokePaint(Hairline, look.muted, round = false)
  for (i in 0..10) {
    val deg = i * 18f
    val len = arc.radius * (if (i % 5 == 0) 0.16f else 0.08f).rf
    drawLine(tick, arc.point(deg, arc.radius - 4f.rf), arc.point(deg, arc.radius - 4f.rf - len))
  }
  val pivot = RemoteOffset(arc.cx, arc.baseY)
  rotate(fraction * 180f.rf, pivot) {
    drawLine(
      strokePaint(4f, look.accent),
      pivot,
      RemoteOffset(arc.cx - arc.radius * 0.86f.rf, arc.baseY),
    )
  }
  fillCircle(fillPaint(look.text), arc.cx, arc.baseY, arc.radius * 0.07f.rf)
}

/**
 * A tuning scale along a main axis: a hairline spine, ticks every tenth (the ends and middle long),
 * and the orange pointer across it at the reading.
 */
private fun RemoteDrawScope.tuningScale(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
  vertical: Boolean,
  start: RemoteFloat,
  len: RemoteFloat,
  cross: RemoteFloat,
  thick: RemoteFloat,
) {
  val line = (thick * 0.08f.rf).min(Hairline.rf)
  val spine = cross + thick * 0.5f.rf - line / 2f.rf
  val hot = zones.hotFrom
  for (i in 0..10) {
    val tickLen = thick * (if (i % 5 == 0) 0.5f else 0.25f).rf
    val at = len * (i / 10f).rf
    val tickColor = if (hot != null && i / 10f >= hot) look.hot else look.muted
    if (vertical) {
      fillRect(tickColor, spine, start + len - at - line / 2f.rf, tickLen, line)
    } else {
      fillRect(tickColor, start + at - line / 2f.rf, spine - tickLen + line, line, tickLen)
    }
  }
  val pointer = (thick * 0.14f.rf).min(6f.rf)
  if (vertical) {
    fillRect(look.muted, spine, start, line, len)
    val y = start + len * (1f.rf - fraction)
    fillRect(look.accent, cross, y - pointer / 2f.rf, thick, pointer)
  } else {
    fillRect(look.muted, start, spine, len, line)
    val x = start + len * fraction
    fillRect(look.accent, x - pointer / 2f.rf, cross, pointer, thick)
  }
}

internal fun RemoteDrawScope.braunBar(look: MeterLook, fraction: RemoteFloat, vertical: Boolean) {
  if (vertical) tuningScale(look, fraction, HaMeterZones(), true, 0f.rf, height, 0f.rf, width)
  else tuningScale(look, fraction, HaMeterZones(), false, 0f.rf, width, 0f.rf, height)
}

/** A vertical tuning scale, at 1 : 3.2; its ticks past the hot mark printed red. */
internal fun RemoteDrawScope.braunThermometer(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 1f / 3.2f)
  tuningScale(look, fraction, zones, true, box.top, box.height, box.left, box.width)
}

/** A hairline, softly rounded battery with a slim charge bar. */
internal fun RemoteDrawScope.braunBattery(look: MeterLook, fraction: RemoteFloat) {
  val box = FitBox(width, height, 2.2f)
  val nubW = box.width * 0.05f.rf
  val bodyW = box.width - nubW
  val stroke = (box.height * 0.07f.rf).min(Hairline.rf)
  val corner = box.height * 0.3f.rf
  fillRound(look.muted, box.left, box.top, bodyW, box.height, corner)
  fillRound(
    look.surface,
    box.left + stroke,
    box.top + stroke,
    bodyW - stroke * 2f.rf,
    box.height - stroke * 2f.rf,
    corner - stroke,
  )
  fillRound(
    look.muted,
    box.left + bodyW,
    box.top + box.height * 0.35f.rf,
    nubW,
    box.height * 0.3f.rf,
    nubW / 2f.rf,
  )
  val inset = box.height * 0.28f.rf
  roundedBar(
    fillPaint(look.value),
    box.left + inset,
    box.top + inset,
    (bodyW - inset * 2f.rf) * fraction,
    box.height - inset * 2f.rf,
    vertical = false,
  )
}
