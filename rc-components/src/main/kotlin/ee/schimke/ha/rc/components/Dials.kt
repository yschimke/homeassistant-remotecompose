@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------------------------
// The 270° control dial (thermostat, humidifier, light) in each visual family. The dial opens at
// 135° (bottom left) and sweeps clockwise to 45° (bottom right); the centre is left for the card's
// text. Every design is live: fills sweep, cells light and pointers swing with the animated value
// fraction, and the target marker rides the target fraction (hidden while [targetVisible] is
// false). Classic is RemoteHaArcDial's own drawing; Console's knob is in MetersConsole.kt.
// ---------------------------------------------------------------------------------------------

/** The square a dial is drawn in: [side] wide at ([left], [top]). */
internal class DialBox(val left: RemoteFloat, val top: RemoteFloat, val side: RemoteFloat) {
  val radius: RemoteFloat = side / 2f.rf
  val cx: RemoteFloat = left + radius
  val cy: RemoteFloat = top + radius
  val center: RemoteOffset = RemoteOffset(cx, cy)

  /** The oval for an arc of radius `radius − inset`. */
  fun topLeft(inset: Float): RemoteOffset = RemoteOffset(left + inset.rf, top + inset.rf)

  fun size(inset: Float): RemoteSize = RemoteSize(side - (inset * 2f).rf, side - (inset * 2f).rf)

  fun point(degrees: Float, r: RemoteFloat): RemoteOffset = polar(cx, cy, degrees, r)
}

private const val DialStart = 135f
private const val DialSweep = 270f

/** [color], or transparent while [visible] is false. */
private fun shown(color: RemoteColor, visible: RemoteBoolean?): RemoteColor =
  visible?.select(color, Color.Transparent.rc) ?: color

/**
 * The dial for [family] — every family but Classic and Console, which [RemoteHaArcDial] draws
 * itself. [stroke] is the classic track stroke, in document pixels, that each design scales from.
 */
internal fun RemoteDrawScope.familyDial(
  family: HaVisualFamily,
  look: MeterLook,
  value: RemoteFloat,
  target: RemoteFloat?,
  targetVisible: RemoteBoolean?,
  box: DialBox,
  stroke: Float,
) {
  when (family) {
    HaVisualFamily.Expressive -> expressiveDial(look, value, target, targetVisible, box, stroke)
    HaVisualFamily.Trinitron -> trinitronDial(look, value, target, targetVisible, box, stroke)
    HaVisualFamily.Lcd -> lcdDial(look, value, target, targetVisible, box, stroke)
    HaVisualFamily.Braun -> braunDial(look, value, target, targetVisible, box, stroke)
    HaVisualFamily.Nixie -> nixieDial(look, value, target, targetVisible, box, stroke)
    HaVisualFamily.Mushroom,
    HaVisualFamily.Bubble,
    HaVisualFamily.Minimalist,
    HaVisualFamily.Cupertino,
    HaVisualFamily.Catppuccin,
    HaVisualFamily.Obsidian,
    HaVisualFamily.Noctis,
    HaVisualFamily.Lcars,
    HaVisualFamily.Arctic,
    HaVisualFamily.Aeon -> {
      communityRing(
        family,
        look,
        value,
        box.cx,
        box.cy,
        box.radius - (stroke * 1.8f).rf,
        DialStart,
        DialSweep,
        stroke,
      )
      if (target != null)
        rotate(target * DialSweep.rf, box.center) {
          drawCircle(
            fillPaint(shown(look.text, targetVisible)),
            (stroke * 0.35f).rf,
            box.point(DialStart, box.radius - (stroke * 1.8f).rf),
          )
        }
    }
    HaVisualFamily.Classic,
    HaVisualFamily.Console -> Unit
  }
}

/** M3 Expressive: a fat round value arc, a gap, the track, and a stop dot at the end. */
private fun RemoteDrawScope.expressiveDial(
  look: MeterLook,
  value: RemoteFloat,
  target: RemoteFloat?,
  targetVisible: RemoteBoolean?,
  box: DialBox,
  stroke: Float,
) {
  val s = stroke * 1.6f
  val inset = (s - stroke) / 2f
  val sweep = value * DialSweep.rf
  val gap = 14f.rf
  drawArc(
    strokePaint(s, look.track),
    DialStart.rf + sweep + gap,
    remoteMax(DialSweep.rf - sweep - gap, 0f.rf),
    false,
    box.topLeft(inset),
    box.size(inset),
  )
  val r = box.radius - inset.rf
  drawCircle(fillPaint(look.value), (s * 0.2f).rf, box.point(DialStart + DialSweep, r))
  drawArc(
    strokePaint(s, look.value),
    DialStart.rf,
    sweep,
    false,
    box.topLeft(inset),
    box.size(inset),
  )
  if (target != null) {
    rotate(target * DialSweep.rf, box.center) {
      val p = box.point(DialStart, r)
      drawCircle(fillPaint(shown(look.surface, targetVisible)), (s * 0.42f).rf, p)
      drawCircle(fillPaint(shown(look.text, targetVisible)), (s * 0.26f).rf, p)
    }
  }
}

/** Trinitron: a ring of LED cells lit to the value inside a hairline index scale, a white index. */
private fun RemoteDrawScope.trinitronDial(
  look: MeterLook,
  value: RemoteFloat,
  target: RemoteFloat?,
  targetVisible: RemoteBoolean?,
  box: DialBox,
  stroke: Float,
) {
  val cells = 36
  val pitch = DialSweep / cells
  val s = stroke * 1.1f
  val inset = s / 2f + stroke * 0.9f
  for (i in 0 until cells) {
    drawArc(
      strokePaint(s, look.segment(value, i, cells, HaMeterZones()), round = false),
      (DialStart + i * pitch + pitch * 0.2f).rf,
      (pitch * 0.6f).rf,
      false,
      box.topLeft(inset),
      box.size(inset),
    )
  }
  val outer = box.radius - 1f.rf
  drawArc(
    strokePaint(1.5f, look.muted, round = false),
    DialStart.rf,
    DialSweep.rf,
    false,
    box.topLeft(1f),
    box.size(1f),
  )
  val tick = strokePaint(1.5f, look.muted, round = false)
  for (i in 0..10) {
    val deg = DialStart + i * (DialSweep / 10f)
    val len = (stroke * if (i % 5 == 0) 0.8f else 0.45f).rf
    drawLine(tick, box.point(deg, outer), box.point(deg, outer - len))
  }
  if (target != null) {
    rotate(target * DialSweep.rf, box.center) {
      drawLine(
        strokePaint(2.5f, shown(look.text, targetVisible), round = false),
        box.point(DialStart, outer + 1f.rf),
        box.point(DialStart, box.radius - (inset + s).rf),
      )
    }
  }
}

/**
 * LCD: fifteen chunky blocks with their ghosts, and a pointer bar outside the ring at the target.
 */
private fun RemoteDrawScope.lcdDial(
  look: MeterLook,
  value: RemoteFloat,
  target: RemoteFloat?,
  targetVisible: RemoteBoolean?,
  box: DialBox,
  stroke: Float,
) {
  val blocks = 15
  val pitch = DialSweep / blocks
  val s = stroke * 1.8f
  val inset = s / 2f + stroke * 0.8f
  for (i in 0 until blocks) {
    drawArc(
      strokePaint(s, look.segment(value, i, blocks, HaMeterZones()), round = false),
      (DialStart + i * pitch + pitch * 0.1f).rf,
      (pitch * 0.8f).rf,
      false,
      box.topLeft(inset),
      box.size(inset),
    )
  }
  if (target != null) {
    rotate(target * DialSweep.rf, box.center) {
      drawLine(
        strokePaint(stroke * 0.7f, shown(look.value, targetVisible), round = false),
        box.point(DialStart, box.radius),
        box.point(DialStart, box.radius - (stroke * 0.7f).rf),
      )
    }
  }
}

/** Braun: a hairline scale with index ticks, the value a pointer bar across it in the accent. */
private fun RemoteDrawScope.braunDial(
  look: MeterLook,
  value: RemoteFloat,
  target: RemoteFloat?,
  targetVisible: RemoteBoolean?,
  box: DialBox,
  stroke: Float,
) {
  val inset = stroke * 0.6f
  val r = box.radius - inset.rf
  drawArc(
    strokePaint(2f, look.muted),
    DialStart.rf,
    DialSweep.rf,
    false,
    box.topLeft(inset),
    box.size(inset),
  )
  val tick = strokePaint(2f, look.muted, round = false)
  for (i in 0..27) {
    val deg = DialStart + i * (DialSweep / 27f)
    val len = (stroke * if (i % 9 == 0) 1.1f else 0.55f).rf
    drawLine(tick, box.point(deg, r), box.point(deg, r - len))
  }
  // The pointer: a bar across the scale at the reading, the way a Braun dial shows its station.
  rotate(value * DialSweep.rf, box.center) {
    drawLine(
      strokePaint(stroke * 0.6f, look.value),
      box.point(DialStart, r + (stroke * 0.5f).rf),
      box.point(DialStart, r - (stroke * 2.4f).rf),
    )
  }
  if (target != null) {
    rotate(target * DialSweep.rf, box.center) {
      val p = box.point(DialStart, r)
      drawCircle(fillPaint(shown(look.text, targetVisible)), (stroke * 0.35f).rf, p)
    }
  }
}

/**
 * Nixie: electrode dots round the scale and a glowing filament to the value, a lit bead at target.
 */
private fun RemoteDrawScope.nixieDial(
  look: MeterLook,
  value: RemoteFloat,
  target: RemoteFloat?,
  targetVisible: RemoteBoolean?,
  box: DialBox,
  stroke: Float,
) {
  val core = stroke * 0.45f
  val inset = core * 2f
  val r = box.radius - inset.rf
  for (i in 0..30) {
    val p = box.point(DialStart + i * (DialSweep / 30f), r)
    drawCircle(fillPaint(look.ghost), (core * 0.6f).rf, p)
  }
  val sweep = value * DialSweep.rf
  look.glow?.let {
    drawArc(
      strokePaint(core * 4f, it),
      DialStart.rf,
      sweep,
      false,
      box.topLeft(inset),
      box.size(inset),
    )
  }
  drawArc(
    strokePaint(core, look.value),
    DialStart.rf,
    sweep,
    false,
    box.topLeft(inset),
    box.size(inset),
  )
  if (target != null) {
    rotate(target * DialSweep.rf, box.center) {
      val p = box.point(DialStart, r)
      look.glow?.let { drawCircle(fillPaint(shown(it, targetVisible)), (core * 2.4f).rf, p) }
      drawCircle(fillPaint(shown(look.text, targetVisible)), core.rf, p)
    }
  }
}
