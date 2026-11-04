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
import androidx.compose.remote.creation.compose.state.selectIfGe
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
  val Dial = HaDialRenderer { spec -> grammarDial(spec) }
}

/**
 * The gap between [segments] cells as a share of the run: 2.5 %, but never more than 30 % of a
 * cell's pitch, so every count [HaComponentGrammar] accepts (up to 48) leaves cells of positive
 * width.
 */
internal fun segmentGapShare(segments: Int): Float = minOf(0.025f, 0.3f / segments)

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

// Live state that picks a colour — a segment lit or not, a tick past the hot mark — is drawn as
// layers rather than a colour select: the mark in its resting colour, then each other colour over
// it, sized by a 0..1 float switch. A float expression plays everywhere; a colour expression does
// not fold in a launcher widget's platform player, which re-bakes on every change instead.

/** 1 when [value] ≥ [threshold], else 0. */
private fun ge(value: RemoteFloat, threshold: RemoteFloat): RemoteFloat =
  selectIfGe(value, threshold, 1f.rf, 0f.rf)

/**
 * The colours segment [index] of [count] lights in over its ghost, each with the 0..1 switch that
 * shows it: lit once the value passes the segment's midpoint, in the hot colour inside a zone.
 */
private fun HaVisualSpec.segmentLayers(
  index: Int,
  count: Int,
): List<Pair<RemoteColor, RemoteFloat>> {
  val mid = (index + 0.5f) / count
  val lit = ge(fraction, mid.rf)
  val live = zones.hotFromLive
  val static = zones.hotFrom
  val low = zones.lowBelow
  return when {
    live != null -> {
      val hot = ge(mid.rf, live)
      listOf(look.value to lit * (1f.rf - hot), look.hot to lit * hot)
    }
    static != null && mid >= static -> listOf(look.hot to lit)
    low != null -> {
      val isLow = 1f.rf - ge(fraction, low.rf)
      listOf(look.value to lit * (1f.rf - isLow), look.hot to lit * isLow)
    }
    else -> listOf(look.value to lit)
  }
}

/**
 * The 0..1 switch that shows a scale tick at [at] in the hot colour over its muted one, or null
 * when it never is. A live mark (a gauge's bound `severity_red`) is compared in the document, so
 * the ticks follow it. A mark at 1 or above means no hot end (`gaugeZones` uses 1 for "none"), so
 * the end tick, which sits at exactly 1, stays muted then rather than matching it.
 */
private fun HaVisualSpec.tickHot(at: Float): RemoteFloat? {
  val live = zones.hotFromLive
  val static = zones.hotFrom
  return when {
    live != null -> {
      val past = ge(at.rf, live)
      if (at < 1f) past else past * (1f.rf - ge(live, 1f.rf))
    }
    static != null && static < 1f && at >= static -> 1f.rf
    else -> null
  }
}

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
      // Flat-ended cells: a lit layer of no length then draws nothing, where a round cap would
      // leave a dot.
      for (i in 0 until n) {
        val start = (180f + (i + (1f - share) / 2f) * 180f / n).rf
        val sweep = (180f / n * share).rf
        arc(strokePaint(thick, look.ghost, round = false), start, sweep)
        for ((color, on) in spec.segmentLayers(i, n)) {
          arc(strokePaint(thick, color, round = false), start, sweep * on)
        }
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
    val stroke = (thick * 0.25f).coerceAtLeast(1f)
    val from = arc.point(at * 180f, inner)
    drawLine(
      strokePaint(stroke, spec.look.muted, round = false),
      from,
      arc.point(at * 180f, inner - len),
    )
    spec.tickHot(at)?.let { on ->
      drawLine(
        strokePaint(stroke, spec.look.hot, round = false),
        from,
        arc.point(at * 180f, inner - len * on),
      )
    }
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
      val gap = len * segmentGapShare(segments).rf
      val cell = (len - gap * (segments - 1).toFloat().rf) / segments.toFloat().rf
      for (i in 0 until segments) {
        val a0 = (cell + gap) * i.toFloat().rf
        rect(look.ghost, a0, cell, 0f.rf, cross)
        for ((color, on) in spec.segmentLayers(i, segments)) {
          rect(color, a0, cell, cross * (1f.rf - on) / 2f.rf, cross * on)
        }
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
        val hot = spec.tickHot(at)
        if (vertical) {
          val ty = y + h - len * at.rf - line / 2f.rf
          fillRect(look.muted, x + spine, ty, tick, line)
          hot?.let { fillRect(look.hot, x + spine, ty, tick * it, line) }
        } else {
          val tx = x + len * at.rf - line / 2f.rf
          fillRect(look.muted, tx, y + spine - tick + line, line, tick)
          hot?.let { fillRect(look.hot, tx, y + spine - tick * it + line, line, tick * it) }
        }
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
      rect(look.value, 0f.rf, filled, c0, t)
      // Below a low mark (a battery past critical) the fill turns hot.
      spec.zones.lowBelow?.let { low ->
        val isLow = 1f.rf - ge(f, low.rf)
        rect(look.hot, 0f.rf, filled, c0 + t * (1f.rf - isLow) / 2f.rf, t * isLow)
      }
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

// ---- Control dial --------------------------------------------------------------------------

private const val DialStartDeg = 135f
private const val DialSweepDeg = 270f

/**
 * A control dial's 270° ring in the grammar: a track and a value arc (with a gap, a stop dot and a
 * halo where the grammar has them), a ring of cells, or index ticks with a pointer; then the target
 * marker riding the target fraction — a dot on a round grammar, a tick across the ring on a flat
 * one. The marker hides by size, not colour (see [ge]), so a launcher widget can play it.
 */
private fun RemoteDrawScope.grammarDial(spec: HaDialSpec) {
  val vs = spec.visual
  val g = vs.grammar
  val look = vs.look
  val v = vs.fraction
  val box = spec.box
  val s = (spec.stroke * g.weight).coerceAtLeast(1.5f)
  val halo = g.glow || vs.gradient != null
  val inset = s / 2f + (if (halo) s * 0.9f else 0f) + 1f
  val r = box.radius - inset.rf
  fun arc(paint: RemotePaint, start: RemoteFloat, sweep: RemoteFloat) =
    drawArc(paint, start, sweep, false, box.topLeft(inset), box.size(inset))

  when (g.level) {
    HaLevelStyle.Segmented -> {
      val n = g.segments.coerceIn(8, 36)
      val pitch = DialSweepDeg / n
      for (i in 0 until n) {
        val start = (DialStartDeg + i * pitch + pitch * 0.12f).rf
        val sweep = (pitch * 0.76f).rf
        arc(strokePaint(s, look.ghost, round = false), start, sweep)
        for ((color, on) in vs.segmentLayers(i, n)) {
          arc(strokePaint(s, color, round = false), start, sweep * on)
        }
      }
    }
    HaLevelStyle.Pointer -> {
      arc(
        strokePaint((s * 0.5f).coerceAtLeast(1f), look.track, g.roundCaps),
        DialStartDeg.rf,
        DialSweepDeg.rf,
      )
      val count = g.ticks.takeIf { it > 0 } ?: 12
      val tick = strokePaint((s * 0.3f).coerceAtLeast(1f), look.muted, round = false)
      for (i in 0..count) {
        val deg = DialStartDeg + i * DialSweepDeg / count
        val len = (s * if (i == 0 || i == count || i * 2 == count) 1.6f else 0.9f).rf
        drawLine(tick, box.point(deg, r - (s * 0.6f).rf), box.point(deg, r - (s * 0.6f).rf - len))
      }
      // A pointer across the ring, not a needle from the centre: a control dial's centre holds
      // its readout.
      rotate(v * DialSweepDeg.rf, box.center) {
        drawLine(
          strokePaint((s * 0.55f).coerceAtLeast(1.5f), look.value, g.roundCaps),
          box.point(DialStartDeg, r + (s * 0.5f).rf),
          box.point(DialStartDeg, r - (s * 3f).rf),
        )
      }
    }
    HaLevelStyle.Continuous -> {
      val value = v * DialSweepDeg.rf
      val track = strokePaint(s, look.track, g.roundCaps)
      if (g.trackGap) {
        val gap = 10f.rf
        arc(track, DialStartDeg.rf + value + gap, remoteMax(0f.rf, DialSweepDeg.rf - value - gap))
      } else {
        arc(track, DialStartDeg.rf, DialSweepDeg.rf)
      }
      haloPaint(
          vs,
          box.point(DialStartDeg, r),
          box.point(DialStartDeg + DialSweepDeg, r),
          s * 2.4f,
        )
        ?.let { arc(it, DialStartDeg.rf, value) }
      arc(strokePaint(s, look.value, g.roundCaps), DialStartDeg.rf, value)
      if (g.stopDot) {
        drawCircle(fillPaint(look.value), (s * 0.22f).rf, box.point(DialStartDeg + DialSweepDeg, r))
      }
    }
  }

  val target = spec.target ?: return
  val shown = spec.targetVisible?.select(1f.rf, 0f.rf) ?: 1f.rf
  rotate(target * DialSweepDeg.rf, box.center) {
    if (g.roundCaps) {
      drawCircle(fillPaint(look.text), (s * 0.42f).rf * shown, box.point(DialStartDeg, r))
    } else {
      val half = (s * 0.9f).rf * shown
      drawLine(
        strokePaint((s * 0.4f).coerceAtLeast(1.5f), look.text, round = false),
        box.point(DialStartDeg, r + half),
        box.point(DialStartDeg, r - half),
      )
    }
  }
}

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
      val lit = ge(f, 0.5f.rf)
      for (i in 0 until n) {
        val c = bar * (if (g.roundCaps) 0.5f else 0.1f).rf
        val by = iy + (bar + gap) * i.toFloat().rf
        if (i == n - 1) {
          fillRound(look.value, ix, by, iw, bar, c)
        } else {
          fillRound(look.ghost, ix, by, iw, bar, c)
          fillRound(look.value, ix, by + bar * (1f.rf - lit) / 2f.rf, iw, bar * lit, c * lit)
        }
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
