@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf

// Classic: the cards' own visuals, exactly as they drew before uber themes. A smooth arc, a rounded
// bar, a liquid thermometer over its bulb, and an outlined battery.

internal fun RemoteDrawScope.classicArc(
  look: MeterLook,
  fraction: RemoteFloat,
  strokePx: Float,
  baseline: Float,
) {
  val arc = ArcBox(width, height, strokePx / 2f + 1f, baseline)
  drawArc(strokePaint(strokePx, look.track), 180f.rf, 180f.rf, false, arc.topLeft, arc.size)
  drawArc(
    strokePaint(strokePx, look.value),
    180f.rf,
    fraction * 180f.rf,
    false,
    arc.topLeft,
    arc.size,
  )
}

internal fun RemoteDrawScope.classicBar(look: MeterLook, fraction: RemoteFloat, vertical: Boolean) {
  val w = width
  val h = height
  if (vertical) {
    roundedBar(fillPaint(look.track), 0f.rf, 0f.rf, w, h, vertical = true)
    val filled = h * fraction
    roundedBar(fillPaint(look.value), 0f.rf, h - filled, w, filled, vertical = true)
  } else {
    roundedBar(fillPaint(look.track), 0f.rf, 0f.rf, w, h, vertical = false)
    roundedBar(fillPaint(look.value), 0f.rf, 0f.rf, w * fraction, h, vertical = false)
  }
}

/** A liquid thermometer: a round-topped tube over a bulb, at 1 : [ThermometerAspect]. */
internal fun RemoteDrawScope.classicThermometer(look: MeterLook, fraction: RemoteFloat) {
  val box = FitBox(width, height, 1f / ThermometerAspect)
  val cx = box.centerX
  val bulbR = box.width / 2f.rf
  val bulbCy = box.bottom - bulbR
  val tubeW = box.width * 0.5f.rf
  val tubeTop = box.top + tubeW / 2f.rf
  val track = fillPaint(look.track)
  drawRect(track, RemoteOffset(cx - tubeW / 2f.rf, tubeTop), RemoteSize(tubeW, bulbCy - tubeTop))
  fillCircle(track, cx, tubeTop, tubeW / 2f.rf)
  fillCircle(track, cx, bulbCy, bulbR)

  val liquidW = tubeW * 0.5f.rf
  val levelTop = tubeTop + (bulbCy - tubeTop) * (1f.rf - fraction)
  val liquid = fillPaint(look.value)
  drawRect(
    liquid,
    RemoteOffset(cx - liquidW / 2f.rf, levelTop),
    RemoteSize(liquidW, bulbCy - levelTop),
  )
  fillCircle(liquid, cx, levelTop, liquidW / 2f.rf)
  fillCircle(liquid, cx, bulbCy, bulbR * 0.7f.rf)
}

/** An outlined battery with a terminal nub, its charge a bar inside, at [BatteryAspect] : 1. */
internal fun RemoteDrawScope.classicBattery(look: MeterLook, fraction: RemoteFloat) {
  val box = FitBox(width, height, BatteryAspect)
  val nubW = box.width * 0.07f.rf
  val bodyW = box.width - nubW
  val wall = box.height * 0.12f.rf
  val corner = box.height * 0.18f.rf
  fillRound(look.track, box.left, box.top, bodyW, box.height, corner)
  fillRound(
    look.surface,
    box.left + wall,
    box.top + wall,
    bodyW - wall * 2f.rf,
    box.height - wall * 2f.rf,
    corner - wall,
  )
  fillRect(look.track, box.left + bodyW, box.top + box.height * 0.3f.rf, nubW, box.height * 0.4f.rf)
  val inset = wall * 2f.rf
  fillRect(
    look.value,
    box.left + inset,
    box.top + inset,
    (bodyW - inset * 2f.rf) * fraction,
    box.height - inset * 2f.rf,
  )
}
