@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf

// Nixie: gas-discharge tubes. A bar, a thermometer and a battery are IN-9 bargraph tubes — a glass
// capsule with a glowing column that rises with the reading, the thermometer standing in its
// socket. A dial is a thin glowing filament along a faint arc of electrode dots. Everything glows:
// a soft halo first, the hot core over it.

internal fun RemoteDrawScope.nixieArc(
  look: MeterLook,
  fraction: RemoteFloat,
  strokePx: Float,
  baseline: Float,
) {
  val core = strokePx * 0.5f
  val arc = ArcBox(width, height, core * 2f + 1f, baseline)
  // Electrode dots along the scale.
  for (i in 0..20) {
    val deg = i * 9f
    fillCircle(
      fillPaint(look.ghost),
      arc.x(deg, arc.radius),
      arc.y(deg, arc.radius),
      (core * 0.6f).rf,
    )
  }
  val sweep = fraction * 180f.rf
  look.glow?.let {
    drawArc(strokePaint(core * 4f, it), 180f.rf, sweep, false, arc.topLeft, arc.size)
  }
  drawArc(strokePaint(core, look.value), 180f.rf, sweep, false, arc.topLeft, arc.size)
}

/**
 * An IN-9 tube along a main axis: the glass capsule, and the glowing column filling `fraction` of
 * it from the start (the bottom, when [vertical]).
 */
private fun RemoteDrawScope.bargraphTube(
  look: MeterLook,
  fraction: RemoteFloat,
  vertical: Boolean,
  start: RemoteFloat,
  len: RemoteFloat,
  cross: RemoteFloat,
  thick: RemoteFloat,
) {
  val glass = (thick * 0.08f.rf).max2(1f.rf)
  val radius = thick / 2f.rf
  if (vertical) {
    fillRound(look.track, cross, start, thick, len, radius)
    fillRound(
      look.surface,
      cross + glass,
      start + glass,
      thick - glass * 2f.rf,
      len - glass * 2f.rf,
      radius - glass,
    )
  } else {
    fillRound(look.track, start, cross, len, thick, radius)
    fillRound(
      look.surface,
      start + glass,
      cross + glass,
      len - glass * 2f.rf,
      thick - glass * 2f.rf,
      radius - glass,
    )
  }
  val inner = len - thick
  val filled = inner * fraction
  val column = thick * 0.18f.rf
  val halo = thick * 0.55f.rf
  fun glowLine(color: RemoteColor, w: RemoteFloat) {
    if (vertical) {
      val bottom = start + len - radius
      roundedBar(
        fillPaint(color),
        cross + (thick - w) / 2f.rf,
        bottom - filled,
        w,
        filled,
        vertical = true,
      )
    } else {
      roundedBar(
        fillPaint(color),
        start + radius,
        cross + (thick - w) / 2f.rf,
        filled,
        w,
        vertical = false,
      )
    }
  }
  look.glow?.let { glowLine(it, halo) }
  glowLine(look.value, column)
}

internal fun RemoteDrawScope.nixieBar(look: MeterLook, fraction: RemoteFloat, vertical: Boolean) {
  if (vertical) bargraphTube(look, fraction, true, 0f.rf, height, 0f.rf, width)
  else bargraphTube(look, fraction, false, 0f.rf, width, 0f.rf, height)
}

/** An IN-9 tube standing in its socket, at 1 : 3.4. */
internal fun RemoteDrawScope.nixieThermometer(look: MeterLook, fraction: RemoteFloat) {
  val box = FitBox(width, height, 1f / 3.4f)
  val socketH = box.height * 0.1f.rf
  val tubeW = box.width * 0.62f.rf
  bargraphTube(
    look,
    fraction,
    true,
    box.top,
    box.height - socketH,
    box.centerX - tubeW / 2f.rf,
    tubeW,
  )
  // The socket and its two pins.
  fillRound(
    look.muted,
    box.left,
    box.bottom - socketH,
    box.width,
    socketH * 0.7f.rf,
    socketH * 0.2f.rf,
  )
  val pin = box.width * 0.08f.rf
  fillRect(
    look.muted,
    box.centerX - box.width * 0.22f.rf,
    box.bottom - socketH * 0.3f.rf,
    pin,
    socketH * 0.3f.rf,
  )
  fillRect(
    look.muted,
    box.centerX + box.width * 0.14f.rf,
    box.bottom - socketH * 0.3f.rf,
    pin,
    socketH * 0.3f.rf,
  )
}

/** A horizontal tube with its end cap, at 2.6 : 1. */
internal fun RemoteDrawScope.nixieBattery(look: MeterLook, fraction: RemoteFloat) {
  val box = FitBox(width, height, 2.6f)
  val capW = box.width * 0.07f.rf
  bargraphTube(look, fraction, false, box.left, box.width - capW, box.top, box.height)
  fillRound(
    look.muted,
    box.right - capW,
    box.top + box.height * 0.3f.rf,
    capW,
    box.height * 0.4f.rf,
    capW * 0.3f.rf,
  )
}

private fun RemoteFloat.max2(other: RemoteFloat): RemoteFloat = remoteMax(this, other)
