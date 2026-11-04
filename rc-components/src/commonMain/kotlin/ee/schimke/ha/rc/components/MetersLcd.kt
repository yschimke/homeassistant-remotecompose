@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf

// LCD: an 80s/90s segment display. Every meter is a run of chunky rectangular segments printed on
// the glass, unlit ones faintly visible as ghosts. There is no thermometer bulb: a temperature is a
// framed column of bars beside a tick scale, and the bars past the hot mark light in a second, red
// ink — the coloured overlay segments real LCDs used. A battery is a phone's: a square outline with
// four bars that turn red when it runs low.

/** A run of [count] chunky segments along a main axis, lit from the start, ghosts showing. */
private fun RemoteDrawScope.segmentRun(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
  count: Int,
  gap: Float,
  vertical: Boolean,
  start: RemoteFloat,
  len: RemoteFloat,
  cross: RemoteFloat,
  thick: RemoteFloat,
  fromStart: Boolean = false,
) {
  val pitch = len / count.toFloat().rf
  val block = pitch * (1f - gap).rf
  for (i in 0 until count) {
    val color = look.segment(fraction, i, count, zones, fromStart)
    if (vertical) {
      fillRect(
        color,
        cross,
        start + len - pitch * (i + 1).toFloat().rf + (pitch - block),
        thick,
        block,
      )
    } else {
      fillRect(color, start + pitch * i.toFloat().rf, cross, block, thick)
    }
  }
}

/**
 * A framed column of ten bars beside a tick scale, at 1 : 3. No bulb: the LCD is a bar graph, its
 * hot bars lit in the hot ink.
 */
internal fun RemoteDrawScope.lcdThermometer(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 1f / 3f)
  val stroke = box.width * 0.07f.rf
  val scaleW = box.width * 0.24f.rf
  val frameX = box.left + scaleW + stroke
  val frameW = box.right - frameX
  outlineRect(look.value, frameX, box.top, frameW, box.height, stroke)
  // Ticks beside the frame, one per two bars, the ends long.
  for (i in 0..5) {
    val y = box.bottom - stroke - (box.height - stroke * 2f.rf) * (i / 5f).rf
    val len = if (i == 0 || i == 5) scaleW else scaleW * 0.55f.rf
    fillRect(look.value, frameX - stroke * 0.5f.rf - len, y - stroke / 2f.rf, len, stroke)
  }
  val inset = stroke * 2f.rf
  segmentRun(
    look,
    fraction,
    zones,
    10,
    0.22f,
    true,
    box.top + inset,
    box.height - inset * 2f.rf,
    frameX + inset,
    frameW - inset * 2f.rf,
  )
}

/** A phone battery: square outline, terminal, four bars — red when low. */
internal fun RemoteDrawScope.lcdBattery(
  look: MeterLook,
  fraction: RemoteFloat,
  zones: HaMeterZones,
) {
  val box = FitBox(width, height, 2.1f)
  val nubW = box.width * 0.07f.rf
  val bodyW = box.width - nubW
  val stroke = box.height * 0.11f.rf
  outlineRect(look.value, box.left, box.top, bodyW, box.height, stroke)
  fillRect(look.value, box.left + bodyW, box.top + box.height * 0.3f.rf, nubW, box.height * 0.4f.rf)
  val inset = stroke * 2f.rf
  segmentRun(
    look,
    fraction,
    zones,
    4,
    0.2f,
    false,
    box.left + inset,
    bodyW - inset * 2f.rf,
    box.top + inset,
    box.height - inset * 2f.rf,
    fromStart = true,
  )
}
