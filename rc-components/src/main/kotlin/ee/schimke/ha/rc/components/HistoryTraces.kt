@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import android.graphics.Paint as AndroidPaint
import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.asRemotePaint
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf

// ---------------------------------------------------------------------------------------------
// History traces: the history-graph row drawn in each visual family's own design
// (docs/design/everyday-cards.md §2). The frame — scale ticks, a screen, a glass tube, a marquee —
// is part of the design, so a family reads from a crop of its graph with the colour removed. Every
// sample is still a live RemoteFloat on the bound min / span, so each design moves with a pushed
// window exactly like the classic line.
// ---------------------------------------------------------------------------------------------

/** Every [step]th index of [n], always ending on the newest sample. */
private fun strided(n: Int, step: Int): List<Int> = ((n - 1) downTo 0 step step).toList().reversed()

/**
 * [fractions] (0 = the series minimum, 1 = its maximum) drawn as [family]'s history trace. Classic
 * and Home Assistant are HA's line over a baseline.
 */
internal fun RemoteDrawScope.historyTrace(
  family: HaVisualFamily,
  look: MeterLook,
  fractions: List<RemoteFloat>,
) {
  val n = fractions.size
  if (n < 2) return
  when (family) {
    HaVisualFamily.Classic,
    HaVisualFamily.HomeAssistant -> {
      val p = Plot(this)
      p.baseline(this, look.track)
      p.line(this, fractions, 2f, look.value)
    }
    HaVisualFamily.Expressive -> expressiveTrace(look, fractions)
    HaVisualFamily.Trinitron -> trinitronTrace(look, fractions)
    HaVisualFamily.Lcd -> lcdTrace(look, fractions)
    HaVisualFamily.Braun -> braunTrace(look, fractions)
    HaVisualFamily.Nixie -> nixieTrace(look, fractions)
    HaVisualFamily.Console -> consoleTrace(look, fractions)
    HaVisualFamily.Ikea -> ikeaTrace(look, fractions)
    HaVisualFamily.Mushroom -> mushroomTrace(look, fractions)
    HaVisualFamily.Bubble -> bubbleTrace(look, fractions)
    HaVisualFamily.Minimalist -> minimalistTrace(look, fractions)
    HaVisualFamily.Cupertino -> cupertinoTrace(look, fractions)
    HaVisualFamily.Catppuccin -> catppuccinTrace(look, fractions)
    HaVisualFamily.Obsidian -> obsidianTrace(look, fractions)
    HaVisualFamily.Noctis -> noctisTrace(look, fractions)
    HaVisualFamily.Lcars -> lcarsTrace(look, fractions)
    HaVisualFamily.Arctic -> arcticTrace(look, fractions)
    HaVisualFamily.Aeon -> aeonTrace(look, fractions)
  }
}

// --- Line helpers on a Plot ------------------------------------------------------------------

private fun Plot.line(
  scope: RemoteDrawScope,
  fractions: List<RemoteFloat>,
  widthPx: Float,
  color: RemoteColor,
  round: Boolean = true,
) {
  val n = fractions.size
  val paint = strokePaint(widthPx, color, round)
  for (i in 0 until n - 1) {
    scope.drawLine(
      paint,
      RemoteOffset(x(i, n), y(fractions[i])),
      RemoteOffset(x(i + 1, n), y(fractions[i + 1])),
    )
  }
}

/**
 * A step line over every [stride]th sample: flat to the next step's x, then up or down to it. A
 * stride keeps the steps deliberate instead of a jagged run of one-sample stairs.
 */
private fun Plot.stepped(
  scope: RemoteDrawScope,
  all: List<RemoteFloat>,
  widthPx: Float,
  color: RemoteColor,
  stride: Int,
) {
  val fractions = strided(all.size, stride).map { all[it] }
  val n = fractions.size
  val paint = strokePaint(widthPx, color, round = false)
  for (i in 0 until n - 1) {
    val a = RemoteOffset(x(i, n), y(fractions[i]))
    val corner = RemoteOffset(x(i + 1, n), y(fractions[i]))
    scope.drawLine(paint, a, corner)
    scope.drawLine(paint, corner, RemoteOffset(x(i + 1, n), y(fractions[i + 1])))
  }
}

/**
 * The area under the line, as one column per sample from the trace to the floor; [toward] of the
 * column's height (from the top) is drawn, so a second, shorter pass deepens the tone near the line
 * the way a gradient would.
 */
private fun Plot.area(
  scope: RemoteDrawScope,
  fractions: List<RemoteFloat>,
  color: RemoteColor,
  toward: Float = 1f,
) {
  val n = fractions.size
  val slot = width / (n - 1).toFloat().rf
  for (i in 0 until n) {
    val y = y(fractions[i])
    val x0 = remoteMax(x(i, n) - slot / 2f.rf, left)
    val x1 = (x(i, n) + slot / 2f.rf).min(left + width)
    // Columns abut exactly and are drawn without anti-aliasing: soft edges would leave a seam
    // between neighbours, and an overlap would double a translucent tone into stripes.
    scope.drawRect(
      areaPaint(color),
      RemoteOffset(x0, y),
      RemoteSize(x1 - x0, (bottom - y) * toward.rf),
    )
  }
}

private fun areaPaint(color: RemoteColor) =
  AndroidPaint()
    .apply {
      isAntiAlias = false
      style = AndroidPaint.Style.FILL
    }
    .asRemotePaint()
    .apply { this.color = color }

private fun Plot.baseline(scope: RemoteDrawScope, color: RemoteColor, widthPx: Float = 1f) =
  scope.drawLine(
    strokePaint(widthPx, color, round = false),
    RemoteOffset(left, bottom),
    RemoteOffset(left + width, bottom),
  )

private fun Plot.point(i: Int, fractions: List<RemoteFloat>): RemoteOffset =
  RemoteOffset(x(i, fractions.size), y(fractions[i]))

private fun Plot.last(fractions: List<RemoteFloat>): RemoteOffset =
  point(fractions.size - 1, fractions)

/** A diamond (a square turned 45°) of half-diagonal [r] at [at]. */
private fun RemoteDrawScope.diamond(color: RemoteColor, at: RemoteOffset, r: Float) {
  val side = r * 1.414f
  rotate(45f.rf, at) {
    fillRect(color, at.x - (side / 2f).rf, at.y - (side / 2f).rf, side.rf, side.rf)
  }
}

// --- Families --------------------------------------------------------------------------------

/** M3 Expressive: a tonal area to the floor under a fat round line, ending on a stop dot. */
private fun RemoteDrawScope.expressiveTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 6f, top = 6f, right = 6f, bottom = 2f)
  p.area(this, fractions, look.track)
  p.line(this, fractions, 5f, look.value)
  val end = p.last(fractions)
  drawCircle(fillPaint(look.surface), 7f.rf, end)
  drawCircle(fillPaint(look.value), 5f.rf, end)
}

/**
 * Sony hi-fi: a dotted graticule with a ruled scale of ticks down the left edge, a hairline trace
 * and the newest sample as a peak lamp.
 */
private fun RemoteDrawScope.trinitronTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 14f, top = 4f, right = 4f, bottom = 4f)
  for (g in 0..4) {
    val y = p.top + p.height * (g / 4f).rf
    // Scale: long ticks at the ends and the middle, short ones between.
    val tick = if (g % 2 == 0) 9f else 5f
    fillRect(look.muted, (12f - tick).rf, y, tick.rf, 1f.rf)
    if (g in 1..3) {
      for (d in 0 until 48) {
        if (d % 2 == 0) {
          fillRect(look.track, p.left + p.width * (d / 48f).rf, y, p.width / 48f.rf, 1f.rf)
        }
      }
    }
  }
  fillRect(look.muted, 12f.rf, p.top, 1f.rf, p.height)
  p.baseline(this, look.muted)
  p.line(this, fractions, 1.5f, look.value)
  drawCircle(fillPaint(look.hot), 3.5f.rf, p.last(fractions))
}

/** A segment LCD: a framed screen of segment columns, each ghosted, lit to the sample's height. */
private fun RemoteDrawScope.lcdTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  outlineRect(look.muted, 0f.rf, 0f.rf, width, height, 2f.rf)
  val p = Plot(this, left = 7f, top = 7f, right = 7f, bottom = 7f)
  val columns = strided(fractions.size, 2)
  val cells = 8
  val slot = p.width / columns.size.toFloat().rf
  val bar = slot * 0.72f.rf
  val pitch = p.height / cells.toFloat().rf
  val cell = pitch * 0.74f.rf
  columns.forEachIndexed { c, i ->
    val x = p.left + slot * c.toFloat().rf + (slot - bar) / 2f.rf
    for (k in 0 until cells) {
      fillRect(
        look.segment(fractions[i], k, cells, HaMeterZones()),
        x,
        p.bottom - pitch * (k + 1).toFloat().rf + (pitch - cell),
        bar,
        cell,
      )
    }
  }
}

/** Braun: a hairline trace over a tuning scale of index ticks, the newest sample an orange dot. */
private fun RemoteDrawScope.braunTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 3f, top = 4f, right = 6f, bottom = 12f)
  val n = fractions.size
  val scaleY = p.bottom + 4f.rf
  fillRect(look.muted, p.left, scaleY, p.width, 1f.rf)
  for (t in 0..24) {
    val x = p.left + p.width * (t / 24f).rf
    val long = t % 6 == 0
    fillRect(look.muted, x, scaleY, (if (long) 1.5f else 1f).rf, (if (long) 9f else 4.5f).rf)
  }
  p.line(this, fractions, 1.5f, look.text)
  drawCircle(fillPaint(look.accent), 4.5f.rf, p.point(n - 1, fractions))
}

/**
 * Nixie: the filament glowing inside a glass tube — a capsule outline with electrode caps at each
 * end — instead of on an open card.
 */
private fun RemoteDrawScope.nixieTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val glass = look.glow ?: look.track
  val radius = height / 2f.rf
  drawRoundRect(
    strokePaint(2f, glass),
    RemoteOffset(1f.rf, 1f.rf),
    RemoteSize(width - 2f.rf, height - 2f.rf),
    RemoteOffset(radius, radius),
  )
  // Electrode caps: a short dark band inside each end of the tube.
  fillRound(look.track, radius * 0.45f.rf, height * 0.3f.rf, 3f.rf, height * 0.4f.rf, 1.5f.rf)
  fillRound(
    look.track,
    width - radius * 0.45f.rf - 3f.rf,
    height * 0.3f.rf,
    3f.rf,
    height * 0.4f.rf,
    1.5f.rf,
  )
  // The filament runs between the electrodes, clear of the tube's round ends.
  val p = Plot(this, top = 8f, bottom = 8f, leftRf = radius, rightRf = radius)
  look.glow?.let { p.line(this, fractions, 8f, it) }
  p.line(this, fractions, 2f, look.value)
}

/** Console: the trace in a recessed screen with a faint graticule. */
private fun RemoteDrawScope.consoleTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  fillRound(ConsoleSlot.rc, 0f.rf, 0f.rf, width, height, 3f.rf)
  val p = Plot(this, left = 4f, top = 5f, right = 4f, bottom = 5f)
  for (g in 1..3) {
    fillRect(ConsoleSlotLip.rc, p.left, p.top + p.height * (g / 4f).rf, p.width, 1f.rf)
  }
  p.line(this, fractions, 2f, look.value)
}

/**
 * IKEA showroom: a thick line on a ruled grid, ending in a price tag — a rounded tag with its
 * string hole — at the newest sample.
 */
private fun RemoteDrawScope.ikeaTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 2f, top = 10f, right = 36f, bottom = 10f)
  for (g in 1..3) {
    fillRect(look.track, p.left, p.top + p.height * (g / 4f).rf, p.width, 1f.rf)
  }
  p.baseline(this, look.muted)
  p.line(this, fractions, 3.5f, look.value)
  val end = p.last(fractions)
  drawLine(strokePaint(2f, look.muted), end, RemoteOffset(end.x + 8f.rf, end.y))
  fillRound(look.accent, end.x + 8f.rf, end.y - 10f.rf, 26f.rf, 20f.rf, 4f.rf)
  drawCircle(fillPaint(look.surface), 3f.rf, RemoteOffset(end.x + 14f.rf, end.y))
}

/** Mushroom: a soft filled hill under a round line, seeds sown along it. */
private fun RemoteDrawScope.mushroomTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 4f, top = 6f, right = 4f, bottom = 2f)
  p.area(this, fractions, look.track)
  p.line(this, fractions, 3f, look.value)
  for (i in strided(fractions.size, 6)) {
    val at = p.point(i, fractions)
    drawCircle(fillPaint(look.surface), 4.5f.rf, at)
    drawCircle(fillPaint(look.value), 3f.rf, at)
  }
}

/** Bubble: a soft fill under a fat line strung with beads. */
private fun RemoteDrawScope.bubbleTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 7f, top = 7f, right = 7f, bottom = 2f)
  p.area(this, fractions, look.track)
  p.line(this, fractions, 5f, look.value)
  for (i in strided(fractions.size, 4)) {
    drawCircle(fillPaint(look.value), 6f.rf, p.point(i, fractions))
    drawCircle(fillPaint(look.surface), 2.5f.rf, p.point(i, fractions))
  }
}

/**
 * UI Minimalist: a stepped hairline and nothing else but the range — short index tabs at the top
 * (the series maximum) and bottom (its minimum) of the left edge.
 */
private fun RemoteDrawScope.minimalistTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 12f, top = 3f, right = 2f, bottom = 3f)
  fillRect(look.muted, 0f.rf, p.top, 8f.rf, 1.5f.rf)
  fillRect(look.muted, 0f.rf, p.bottom - 1.5f.rf, 8f.rf, 1.5f.rf)
  fillRect(look.track, 3f.rf, p.top, 1f.rf, p.height)
  p.stepped(this, fractions, 1.5f, look.value, stride = 4)
}

/** Control Centre / Health: a smooth line over a graded area, the newest sample a ringed point. */
private fun RemoteDrawScope.cupertinoTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 3f, top = 7f, right = 7f, bottom = 1f)
  val tint = look.value.copy(alpha = look.value.alpha * 0.07f.rf)
  // Stacked passes, each shorter: the tone is deepest by the line and fades to the floor.
  for (toward in listOf(1f, 0.75f, 0.55f, 0.38f, 0.24f, 0.12f)) {
    p.area(this, fractions, tint, toward = toward)
  }
  p.line(this, fractions, 3.5f, look.value)
  val end = p.last(fractions)
  drawCircle(fillPaint(look.surface), 6f.rf, end)
  drawCircle(strokePaint(2.5f, look.value), 5f.rf, end)
}

/** Catppuccin: a pastel step line over a stitched (dashed) seam. */
private fun RemoteDrawScope.catppuccinTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 3f, top = 4f, right = 3f, bottom = 8f)
  val seam = p.bottom + 4f.rf
  for (d in 0 until 30) {
    if (d % 2 == 0) {
      fillRound(look.muted, p.left + p.width * (d / 30f).rf, seam, p.width / 30f.rf, 2f.rf, 1f.rf)
    }
  }
  p.stepped(this, fractions, 3f, look.value, stride = 3)
}

/** Obsidian Minimal: a hairline on ruled notebook lines. */
private fun RemoteDrawScope.obsidianTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this)
  for (i in 0..4) fillRect(look.track, p.left, p.top + p.height * (i / 4f).rf, p.width, 1f.rf)
  p.line(this, fractions, 1.5f, look.value)
}

/** Noctis: twin rails — a wide dim rail with the trace as a hairline inside it. */
private fun RemoteDrawScope.noctisTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 5f, top = 6f, right = 5f, bottom = 6f)
  p.line(this, fractions, 9f, look.track)
  p.line(this, fractions, 1.5f, look.value)
}

/**
 * LCARS: blocky bars with round caps, grouped into bands of the panel's colours, behind an elbow
 * block at the left.
 */
private fun RemoteDrawScope.lcarsTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  // The elbow: a broad upright band that turns into the floor rail.
  fillRound(look.accent, 0f.rf, 0f.rf, 26f.rf, height, 13f.rf)
  fillRect(look.accent, 13f.rf, height - 7f.rf, width - 13f.rf, 7f.rf)
  fillRound(look.surface, 26f.rf, (-12f).rf, 24f.rf, height - 7f.rf + 12f.rf, 12f.rf)
  val p = Plot(this, left = 34f, top = 2f, right = 0f, bottom = 11f)
  val bars = strided(fractions.size, 3)
  val slot = p.width / bars.size.toFloat().rf
  val w = slot * 0.7f.rf
  val bands = listOf(look.value, look.accent, look.muted)
  bars.forEachIndexed { b, i ->
    val y = p.y(fractions[i]).min(p.bottom - w)
    roundedBar(
      fillPaint(bands[(b / 4) % bands.size]),
      p.left + slot * b.toFloat().rf + (slot - w) / 2f.rf,
      y,
      w,
      p.bottom - y,
      vertical = true,
    )
  }
}

/** Arctic: a hairline over the horizon, with a suspended "now" needle at the newest sample. */
private fun RemoteDrawScope.arcticTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  val p = Plot(this, left = 2f, top = 3f, right = 8f, bottom = 6f)
  val n = fractions.size
  // The horizon: a clear ice rail with a hairline on it.
  fillRect(look.track, p.left, p.bottom, p.width, 4f.rf)
  fillRect(look.muted, p.left, p.bottom, p.width, 1f.rf)
  p.line(this, fractions, 1.5f, look.value)
  // The needle hangs from the top edge down to the horizon, a ring where it meets the trace.
  val x = p.x(n - 1, n)
  drawLine(strokePaint(1f, look.accent), RemoteOffset(x, 0f.rf), RemoteOffset(x, p.bottom + 4f.rf))
  drawCircle(fillPaint(look.accent), 2.5f.rf, RemoteOffset(x, 0f.rf + 2.5f.rf))
  drawCircle(fillPaint(look.surface), 4f.rf, p.last(fractions))
  drawCircle(strokePaint(1.5f, look.accent), 4f.rf, p.last(fractions))
}

/**
 * Aeon cinema marquee: slim bars inside a double-ruled frame, diamond indexes marking the top
 * (maximum) and bottom (minimum) of the range and the newest sample.
 */
private fun RemoteDrawScope.aeonTrace(look: MeterLook, fractions: List<RemoteFloat>) {
  outlineRect(look.accent, 0f.rf, 0f.rf, width, height, 1f.rf)
  outlineRect(look.accent, 3f.rf, 3f.rf, width - 6f.rf, height - 6f.rf, 1f.rf)
  val p = Plot(this, left = 16f, top = 9f, right = 9f, bottom = 9f)
  diamond(look.accent, RemoteOffset(9f.rf, p.top), 3.5f)
  diamond(look.accent, RemoteOffset(9f.rf, p.bottom), 3.5f)
  val bars = strided(fractions.size, 2)
  val slot = p.width / bars.size.toFloat().rf
  val w = slot * 0.45f.rf
  bars.forEachIndexed { b, i ->
    val y = p.y(fractions[i])
    fillRect(look.value, p.left + slot * b.toFloat().rf + (slot - w) / 2f.rf, y, w, p.bottom - y)
  }
  diamond(look.value, p.last(fractions), 4f)
}
