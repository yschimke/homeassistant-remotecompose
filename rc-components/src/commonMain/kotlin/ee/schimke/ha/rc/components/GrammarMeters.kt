@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap

// ---------------------------------------------------------------------------------------------
// Grammar drawings: one drawing of each visual, written against HaComponentGrammar's tokens
// instead of a family. A theme kit (a theme with no bespoke family) draws every visual this way,
// and a new visual drawn here — the lock — works under every theme, bespoke ones included, the
// day it lands. Same rules as every meter (RemoteHaMeters.kt): fit the canvas at its own aspect,
// size everything from the canvas at playback, track in the divider, value in the caller's
// (live) colour or the theme's ink, and let the live fraction place every moving part.
// ---------------------------------------------------------------------------------------------

internal object GrammarRenderers {
  val Arc = HaVisualRenderer { spec -> grammarArc(spec) }
  val Bar = HaVisualRenderer { spec ->
    levelRun(spec, 0f.rf, 0f.rf, width, height, spec.vertical, spec.grammar.segments)
  }
  val Thermometer = HaVisualRenderer { spec -> grammarThermometer(spec) }
  val Battery = HaVisualRenderer { spec -> grammarBattery(spec) }
  val Lock = HaVisualRenderer { spec -> grammarLock(spec) }
}

/** Width : height of [RemoteHaLockGlyph]. */
const val LockAspect = 0.78f

private const val ArcGapDegrees = 9f

/** The grammar's weight, bounded so a hairline still shows and a bold line still fits. */
private val HaComponentGrammar.weight: Float
  get() = stroke.scale

/** A stroke paint whose width is a document expression, so it scales with the canvas. */
private fun strokePaint(width: RemoteFloat, color: RemoteColor, round: Boolean) = RemotePaint {
  isAntiAlias = true
  style = PaintingStyle.Stroke
  strokeWidth = width
  strokeCap = if (round) StrokeCap.Round else StrokeCap.Butt
}
  .apply { this.color = color }

/** The grammar's gradient, unless an ink (which always wins) prints the theme in one colour. */
private val HaVisualSpec.gradient: List<androidx.compose.ui.graphics.Color>?
  get() = grammar.gradient?.takeUnless { look.inked }

/**
 * The halo under a value, or null for none: the grammar's gradient, faint, laid along the scale
 * from [from] to [to]; else, for a glowing grammar, the ink's glow (or the track). The value itself
 * is always drawn over it in its own colour, so a gradient is a brand moment that never hides what
 * a colour means (rule 2): a locked lock stays green, a gauge's severity band stays red.
 * [strokeWidth] makes it a stroke paint.
 */
private fun RemoteDrawScope.haloPaint(
  spec: HaVisualSpec,
  from: RemoteOffset,
  to: RemoteOffset,
  strokeWidth: Float? = null,
): RemotePaint? {
  val colors = spec.gradient
  val solid = if (spec.grammar.glow) spec.look.glow ?: spec.look.track else null
  if (colors == null && solid == null) return null
  val base = solid ?: spec.look.track
  val paint =
    if (strokeWidth != null) strokePaint(strokeWidth, base, round = true) else fillPaint(base)
  if (colors != null) {
    val brush = RemoteBrush.linearGradient(colors.map { it.copy(alpha = HaloAlpha).rc }, from, to)
    with(brush) { this@haloPaint.applyTo(paint, RemoteSize(width, height)) }
  }
  return paint
}

private const val HaloAlpha = 0.45f

// ---- Dial -----------------------------------------------------------------------------------

private fun RemoteDrawScope.grammarArc(spec: HaVisualSpec) {
  val g = spec.grammar
  val look = spec.look
  val f = spec.fraction
  val thick = (spec.strokePx * g.weight).coerceAtLeast(1.5f)
  val pad = (if (g.glow || spec.gradient != null) thick * 1.2f else thick / 2f) + 1f
  val arc = ArcBox(width, height, pad, spec.baseline)
  val round = g.roundCaps
  fun arc(paint: RemotePaint, start: RemoteFloat, sweep: RemoteFloat) =
    drawArc(paint, start, sweep, false, arc.topLeft, arc.size)

  when (g.level) {
    HaLevelStyle.Segmented -> {
      val n = g.segments
      val share = 0.72f
      for (i in 0 until n) {
        val start = 180f + (i + (1f - share) / 2f) * 180f / n
        arc(
          strokePaint(thick, look.segment(f, i, n, spec.zones), round),
          start.rf,
          (180f / n * share).rf,
        )
      }
    }
    HaLevelStyle.Pointer -> {
      arc(strokePaint(thick, look.track, round), 180f.rf, 180f.rf)
      arcTicks(spec, arc, thick, g.ticks.takeIf { it > 0 } ?: 10)
      val pivot = RemoteOffset(arc.cx, arc.baseY)
      rotate(f * 180f.rf, pivot) {
        drawLine(
          strokePaint(thick.coerceAtLeast(2f) * 0.6f, look.value, round),
          pivot,
          RemoteOffset(arc.cx - arc.radius, arc.baseY),
        )
      }
      fillCircle(fillPaint(look.text), arc.cx, arc.baseY, arc.radius * 0.07f.rf)
    }
    HaLevelStyle.Continuous -> {
      val value = f * 180f.rf
      if (g.trackGap) {
        val gap = ArcGapDegrees.rf
        arc(
          strokePaint(thick, look.track, round),
          180f.rf + value + gap,
          remoteMax(0f.rf, 180f.rf - value - gap),
        )
      } else {
        arc(strokePaint(thick, look.track, round), 180f.rf, 180f.rf)
      }
      if (g.ticks > 0) arcTicks(spec, arc, thick, g.ticks)
      haloPaint(
          spec,
          RemoteOffset(arc.cx - arc.radius, arc.baseY),
          RemoteOffset(arc.cx + arc.radius, arc.baseY),
          thick * 2.4f,
        )
        ?.let { arc(it, 180f.rf, value) }
      arc(strokePaint(thick, look.value, round), 180f.rf, value)
      if (g.stopDot) {
        drawCircle(
          fillPaint(look.value),
          (thick * 0.22f).rf,
          RemoteOffset(arc.cx + arc.radius, arc.baseY),
        )
      }
    }
  }
}

/** Index ticks inside a dial; those past a hot zone in the hot colour. */
private fun RemoteDrawScope.arcTicks(spec: HaVisualSpec, arc: ArcBox, thick: Float, count: Int) {
  val inner = arc.radius - (thick * 0.9f).rf
  for (i in 0..count) {
    val at = i.toFloat() / count
    val long = i == 0 || i == count || (count % 2 == 0 && i == count / 2)
    val len = arc.radius * (if (long) 0.14f else 0.07f).rf
    val hot = spec.zones.hotFrom?.let { at >= it } == true
    drawLine(
      strokePaint((thick * 0.25f).coerceAtLeast(1f), if (hot) spec.look.hot else spec.look.muted),
      arc.point(at * 180f, inner),
      arc.point(at * 180f, inner - len),
    )
  }
}

// ---- Straight levels: a bar, a thermometer's column, a battery's charge ----------------------

/**
 * A level along a run of the canvas: [x], [y], [w], [h] in canvas pixels, filling from the start
 * (left, or the bottom when [vertical]) by the fraction, in the grammar's level style.
 */
private fun RemoteDrawScope.levelRun(
  spec: HaVisualSpec,
  x: RemoteFloat,
  y: RemoteFloat,
  w: RemoteFloat,
  h: RemoteFloat,
  vertical: Boolean,
  segments: Int,
) {
  val g = spec.grammar
  val look = spec.look
  val f = spec.fraction
  val len = if (vertical) h else w
  val cross = if (vertical) w else h

  /** A corner for a shape [thickness] across: round, or nearly square. */
  fun corner(thickness: RemoteFloat): RemoteFloat =
    thickness * (if (g.roundCaps) 0.5f else 0.12f).rf

  /** A rect from [a0] to [a0] + [a] along the run, [c0] to [c0] + [c] across it. */
  fun rect(color: RemoteColor, a0: RemoteFloat, a: RemoteFloat, c0: RemoteFloat, c: RemoteFloat) =
    if (vertical) fillRound(color, x + c0, y + h - a0 - a, c, a, corner(c))
    else fillRound(color, x + a0, y + c0, a, c, corner(c))

  when (g.level) {
    HaLevelStyle.Segmented -> {
      val gap = len * 0.025f.rf
      val cell = (len - gap * (segments - 1).toFloat().rf) / segments.toFloat().rf
      for (i in 0 until segments) {
        rect(
          look.segment(f, i, segments, spec.zones),
          (cell + gap) * i.toFloat().rf,
          cell,
          0f.rf,
          cross,
        )
      }
    }
    HaLevelStyle.Pointer -> {
      val line = (cross * 0.1f.rf).min(2f.rf)
      val spine = cross * 0.5f.rf - line / 2f.rf
      val count = g.ticks.takeIf { it > 0 } ?: 10
      for (i in 0..count) {
        val at = i.toFloat() / count
        val long = i == 0 || i == count || (count % 2 == 0 && i == count / 2)
        val tick = cross * (if (long) 0.45f else 0.25f).rf
        val hot = spec.zones.hotFrom?.let { at >= it } == true
        val color = if (hot) look.hot else look.muted
        if (vertical) fillRect(color, x + spine, y + h - len * at.rf - line / 2f.rf, tick, line)
        else fillRect(color, x + len * at.rf - line / 2f.rf, y + spine - tick + line, line, tick)
      }
      if (vertical) fillRect(look.track, x + spine, y, line, len)
      else fillRect(look.track, x, y + spine, len, line)
      val pointer = (cross * 0.16f.rf).min(6f.rf)
      val at = len * f - pointer / 2f.rf
      if (vertical) fillRect(look.value, x, y + h - at - pointer, cross, pointer)
      else fillRect(look.value, x + at, y, pointer, cross)
    }
    HaLevelStyle.Continuous -> {
      // A lighter grammar draws a thinner bar centred in the run; bold fills it.
      val weight = g.weight.coerceAtMost(1f)
      val t = cross * weight.rf
      val c0 = (cross - t) / 2f.rf
      val filled = len * f
      if (g.trackGap) {
        val gap = t * 0.6f.rf
        rect(look.track, filled + gap, remoteMax(0f.rf, len - filled - gap), c0, t)
      } else {
        rect(look.track, 0f.rf, len, c0, t)
      }
      val start = if (vertical) RemoteOffset(x, y + h) else RemoteOffset(x, y)
      val end = if (vertical) RemoteOffset(x, y) else RemoteOffset(x + w, y)
      haloPaint(spec, start, end)?.let { halo ->
        // The halo spreads into the run's spare cross axis, never past the canvas.
        val spread = (cross - t).min(t)
        val ht = t + spread
        val hc = c0 - spread / 2f.rf
        if (vertical)
          drawRoundRect(
            halo,
            RemoteOffset(x + hc, y + h - filled),
            RemoteSize(ht, filled),
            RemoteOffset(corner(ht), corner(ht)),
          )
        else
          drawRoundRect(
            halo,
            RemoteOffset(x, y + hc),
            RemoteSize(filled, ht),
            RemoteOffset(corner(ht), corner(ht)),
          )
      }
      val value =
        when {
          spec.zones.lowBelow != null ->
            f.isGreaterThanOrEqualTo(spec.zones.lowBelow.rf).select(look.value, look.hot)
          else -> look.value
        }
      rect(value, 0f.rf, filled, c0, t)
      if (g.stopDot) {
        val dot = t * 0.22f.rf
        val at = len - t / 2f.rf
        if (vertical)
          drawCircle(fillPaint(look.value), dot, RemoteOffset(x + cross / 2f.rf, y + h - at))
        else drawCircle(fillPaint(look.value), dot, RemoteOffset(x + at, y + cross / 2f.rf))
      }
    }
  }
}

/** A thermometer: a column filling from the bottom, over a bulb when the grammar is round. */
private fun RemoteDrawScope.grammarThermometer(spec: HaVisualSpec) {
  val g = spec.grammar
  val box = FitBox(width, height, 1f / ThermometerAspect)
  if (g.level == HaLevelStyle.Pointer) {
    levelRun(spec, box.left, box.top, box.width, box.height, vertical = true, g.segments)
    return
  }
  val bulb = g.roundCaps && g.level == HaLevelStyle.Continuous
  val tubeW = box.width * (if (bulb) 0.5f else 0.62f).rf
  val tubeX = box.centerX - tubeW / 2f.rf
  val bulbR = box.width / 2f.rf
  val runH = if (bulb) box.height - bulbR else box.height
  levelRun(spec, tubeX, box.top, tubeW, runH, vertical = true, minOf(g.segments, 12))
  if (bulb) {
    fillCircle(fillPaint(spec.look.track), box.centerX, box.bottom - bulbR, bulbR)
    fillCircle(fillPaint(spec.look.value), box.centerX, box.bottom - bulbR, bulbR * 0.72f.rf)
  }
}

/** A battery: a body with a terminal nub, its charge a level inside. */
private fun RemoteDrawScope.grammarBattery(spec: HaVisualSpec) {
  val g = spec.grammar
  val look = spec.look
  val box = FitBox(width, height, BatteryAspect)
  val nubW = box.width * 0.07f.rf
  val bodyW = box.width - nubW
  val wall = box.height * (0.09f * g.weight.coerceIn(0.6f, 1.6f)).rf
  val corner = box.height * (if (g.roundCaps) 0.26f else 0.08f).rf
  fillRound(look.track, box.left, box.top, bodyW, box.height, corner)
  fillRound(
    look.surface,
    box.left + wall,
    box.top + wall,
    bodyW - wall * 2f.rf,
    box.height - wall * 2f.rf,
    remoteMax(0f.rf, corner - wall),
  )
  fillRound(
    look.track,
    box.left + bodyW,
    box.top + box.height * 0.3f.rf,
    nubW,
    box.height * 0.4f.rf,
    nubW * (if (g.roundCaps) 0.5f else 0f).rf,
  )
  val inset = wall * 2f.rf
  // A pointer reads poorly on a battery: its charge is a fill under every grammar but segments.
  val level =
    if (g.level == HaLevelStyle.Pointer) spec.copyGrammar(g.copy(level = HaLevelStyle.Continuous))
    else spec
  levelRun(
    level,
    box.left + inset,
    box.top + inset,
    bodyW - inset * 2f.rf,
    box.height - inset * 2f.rf,
    vertical = false,
    segments = minOf(g.segments, 5).coerceAtLeast(4),
  )
}

private fun HaVisualSpec.copyGrammar(grammar: HaComponentGrammar) =
  HaVisualSpec(look, fraction, grammar, zones, strokePx, baseline, vertical)

// ---- Lock -----------------------------------------------------------------------------------

/**
 * A padlock at [LockAspect]: a shackle over a body, the shackle seated when the fraction is 1
 * (locked) and lifted clear at 0. The body shows the state in the grammar's way: a solid body with
 * a keyhole, an outlined body with lit bars (segmented), or a bolt sliding into its keeper
 * (pointer).
 */
private fun RemoteDrawScope.grammarLock(spec: HaVisualSpec) {
  val g = spec.grammar
  val look = spec.look
  val f = spec.fraction
  val box = FitBox(width, height, LockAspect)
  val bw = box.width
  val lift = box.height * 0.14f.rf
  val bodyTop = box.top + box.height * 0.44f.rf
  val bodyH = box.bottom - bodyTop
  val corner = bw * (if (g.roundCaps) 0.2f else 0.05f).rf

  // The shackle: a U whose legs run into the body, seated by the live fraction.
  val st = bw * (0.12f * g.weight.coerceIn(0.5f, 1.4f)).rf
  val r = (bw * 0.62f.rf - st) / 2f.rf
  val arcCy = box.top + st / 2f.rf + r + lift * f
  val legBottom = bodyTop + st
  val shackle = strokePaint(st, look.muted, g.roundCaps)
  drawArc(
    shackle,
    180f.rf,
    180f.rf,
    false,
    RemoteOffset(box.centerX - r, arcCy - r),
    RemoteSize(r * 2f.rf, r * 2f.rf),
  )
  // Butt-ended legs, so a round cap doesn't poke out under the body.
  val leg = strokePaint(st, look.muted, round = false)
  drawLine(leg, RemoteOffset(box.centerX - r, arcCy), RemoteOffset(box.centerX - r, legBottom))
  // The free leg lifts clear of the body as it unlocks, so an open lock shows a gap — not just a
  // taller shackle — whatever the body's colour or ink.
  val freeBottom = legBottom - (1f.rf - f) * (st + lift * 0.6f.rf)
  drawLine(leg, RemoteOffset(box.centerX + r, arcCy), RemoteOffset(box.centerX + r, freeBottom))

  haloPaint(spec, RemoteOffset(box.left, bodyTop), RemoteOffset(box.right, box.bottom))?.let {
    val halo = st * 0.6f.rf
    drawRoundRect(
      it,
      RemoteOffset(box.left - halo, bodyTop - halo),
      RemoteSize(bw + halo * 2f.rf, bodyH + halo * 2f.rf),
      RemoteOffset(corner + halo, corner + halo),
    )
  }

  val solid = !g.outline && g.level == HaLevelStyle.Continuous
  if (solid) {
    fillRound(look.value, box.left, bodyTop, bw, bodyH, corner)
    keyhole(box.centerX, bodyTop, bw, bodyH, look.surface)
    return
  }

  // An outlined body: a wall in the value colour (or the track, for a lit-segment or bolt body,
  // whose state is carried by what's inside), hollowed to the card surface.
  val wall = (bw * (0.07f * g.weight.coerceIn(0.5f, 1.6f)).rf)
  val frame = if (g.level == HaLevelStyle.Continuous) look.value else look.track
  fillRound(frame, box.left, bodyTop, bw, bodyH, corner)
  fillRound(
    look.surface,
    box.left + wall,
    bodyTop + wall,
    bw - wall * 2f.rf,
    bodyH - wall * 2f.rf,
    remoteMax(0f.rf, corner - wall),
  )
  val ix = box.left + wall * 2f.rf
  val iy = bodyTop + wall * 2f.rf
  val iw = bw - wall * 4f.rf
  val ih = bodyH - wall * 4f.rf
  when (g.level) {
    HaLevelStyle.Continuous -> keyhole(box.centerX, bodyTop, bw, bodyH, look.value)
    HaLevelStyle.Segmented -> {
      // Three bars: the bottom one always lit in the state's colour (so unlocked still reads as
      // its warning), the others lit with it once locked past halfway — a lock is two-state.
      val n = 3
      val gap = ih * 0.12f.rf
      val bar = (ih - gap * (n - 1).toFloat().rf) / n.toFloat().rf
      val lit = f.isGreaterThanOrEqualTo(0.5f.rf).select(look.value, look.ghost)
      for (i in 0 until n) {
        val c = bar * (if (g.roundCaps) 0.5f else 0.1f).rf
        fillRound(
          if (i == n - 1) look.value else lit,
          ix,
          iy + (bar + gap) * i.toFloat().rf,
          iw,
          bar,
          c,
        )
      }
    }
    HaLevelStyle.Pointer -> {
      // A bolt from the left wall, sliding home into a keeper on the right as it locks.
      val boltH = ih * 0.3f.rf
      val by = iy + (ih - boltH) / 2f.rf
      val keeper = iw * 0.14f.rf
      fillRect(look.muted, ix + iw - keeper, iy, keeper, ih)
      fillRect(look.value, ix, by, iw * (0.35f.rf + 0.65f.rf * f), boltH)
    }
  }
}

/** A keyhole on a lock body: a round ward over a slot. */
private fun RemoteDrawScope.keyhole(
  cx: RemoteFloat,
  bodyTop: RemoteFloat,
  bw: RemoteFloat,
  bodyH: RemoteFloat,
  color: RemoteColor,
) {
  val cy = bodyTop + bodyH * 0.42f.rf
  val r = bw * 0.08f.rf
  fillCircle(fillPaint(color), cx, cy, r)
  val slotW = r * 0.9f.rf
  fillRect(color, cx - slotW / 2f.rf, cy, slotW, bodyH * 0.28f.rf)
}
