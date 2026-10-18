@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.ui.graphics.Color

// Furniture, not instruments: a VARMBLIXT-like rounded light ring, KALLAX compartments,
// a timber-backed temperature rail and LADDA-like rechargeable cells. Geometry and fill stay
// expressions of the canvas and bound reading, including in baked launcher documents.
internal fun RemoteDrawScope.ikeaArc(look: MeterLook, fraction: RemoteFloat, baseline: Float) {
  val arc = ArcBox(width, height, 8f, baseline)
  drawArc(strokePaint(14f, look.track), 180f.rf, 180f.rf, false, arc.topLeft, arc.size)
  drawArc(strokePaint(10f, look.value), 180f.rf, fraction * 180f.rf, false, arc.topLeft, arc.size)
  // Printed index marks distinguish the range even when the light ring is empty.
  for (i in 0..4) {
    drawLine(
      strokePaint(1.5f, look.muted),
      arc.point(i * 45f, arc.radius - 12f.rf),
      arc.point(i * 45f, arc.radius - 16f.rf),
    )
  }
}

internal fun RemoteDrawScope.ikeaBar(look: MeterLook, fraction: RemoteFloat, vertical: Boolean) {
  val count = 8
  for (i in 0 until count) {
    val color = look.segment(fraction, i, count, HaMeterZones(), fromStart = true)
    if (vertical) {
      val pitch = height / count.toFloat().rf
      fillRound(
        color,
        0f.rf,
        height - pitch * (i + 1).toFloat().rf,
        width,
        pitch * 0.78f.rf,
        1f.rf,
      )
    } else {
      val pitch = width / count.toFloat().rf
      fillRound(color, pitch * i.toFloat().rf, 0f.rf, pitch * 0.78f.rf, height, 1f.rf)
    }
  }
}

internal fun RemoteDrawScope.ikeaThermometer(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 0.34f)
  fillRound(look.track, box.left, box.top, box.width, box.height, 3f.rf)
  fillRound(
    look.surface,
    box.left + box.width * 0.15f.rf,
    box.top + 3f.rf,
    box.width * 0.7f.rf,
    box.height - 6f.rf,
    2f.rf,
  )
  val inner = box.height - 12f.rf
  val color =
    zones.hotFrom?.let { fraction.isGreaterThanOrEqualTo(it.rf).select(look.hot, look.value) }
      ?: look.value
  fillRound(
    color,
    box.left + box.width * 0.25f.rf,
    box.top + 6f.rf + inner * (1f.rf - fraction),
    box.width * 0.25f.rf,
    inner * fraction,
    1f.rf,
  )
  for (i in 0..8) {
    fillRect(
      look.muted,
      box.left + box.width * 0.66f.rf,
      box.top + 6f.rf + inner * (i / 8f).rf,
      box.width * 0.18f.rf,
      1f.rf,
    )
  }
}

internal fun RemoteDrawScope.ikeaBattery(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 2.1f)
  fillRound(look.track, box.left, box.top, box.width * 0.94f.rf, box.height, box.height * 0.12f.rf)
  fillRect(
    look.muted,
    box.left + box.width * 0.94f.rf,
    box.top + box.height * 0.3f.rf,
    box.width * 0.06f.rf,
    box.height * 0.4f.rf,
  )
  for (i in 0..3) {
    val pitch = box.width * 0.21f.rf
    fillRound(
      look.segment(fraction, i, 4, zones, fromStart = true),
      box.left + box.width * 0.055f.rf + pitch * i.toFloat().rf,
      box.top + box.height * 0.15f.rf,
      pitch * 0.83f.rf,
      box.height * 0.7f.rf,
      1f.rf,
    )
  }
}

internal fun RemoteDrawScope.ikeaDial(
  look: MeterLook,
  value: RemoteFloat,
  target: RemoteFloat?,
  targetVisible: RemoteBoolean?,
  box: DialBox,
  stroke: Float,
) {
  // A thick opal ring, with an inset coloured light and restrained printed scale.
  drawArc(
    strokePaint(stroke + 4f, look.track),
    135f.rf,
    270f.rf,
    false,
    box.topLeft(0f),
    box.size(0f),
  )
  drawArc(
    strokePaint(stroke - 2f, look.value),
    135f.rf,
    value * 270f.rf,
    false,
    box.topLeft(0f),
    box.size(0f),
  )
  for (i in 0..6) {
    val p = box.point(135f + i * 45f, box.radius - (stroke + 5f).rf)
    drawCircle(fillPaint(look.muted), 1f.rf, p)
  }
  if (target != null) {
    val color = targetVisible?.select(look.text, Color.Transparent.rc) ?: look.text
    rotate(target * 270f.rf, box.center) {
      drawCircle(fillPaint(color), 3f.rf, box.point(135f, box.radius))
    }
  }
}
