@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.layout.RemoteDrawScope
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rf

// ---------------------------------------------------------------------------------------------
// Graph rows (history-graph sparklines, statistics-graph bars) in each visual family. Like the
// meters, a family is a different *design*: an LCD history is a bar graph of segments with its
// ghosts, a Nixie line is a glowing filament, a Console graph sits in a recessed screen. Every
// sample is a live RemoteFloat, so each design moves with the pushed window like the classic line.
// ---------------------------------------------------------------------------------------------

/**
 * The plot area of a graph row canvas, inset so strokes and dots aren't clipped. A design that
 * frames its trace (a scale, a screen, a tube) insets further; [leftRf] / [rightRf] take an inset
 * that depends on the canvas size.
 */
internal class Plot(
  scope: RemoteDrawScope,
  left: Float = 2f,
  top: Float = 3f,
  right: Float = 2f,
  bottom: Float = 3f,
  leftRf: RemoteFloat? = null,
  rightRf: RemoteFloat? = null,
) {
  val left: RemoteFloat = leftRf ?: left.rf
  val top: RemoteFloat = top.rf
  val width: RemoteFloat = scope.width - this.left - (rightRf ?: right.rf)
  val height: RemoteFloat = scope.height - (top + bottom).rf
  val bottom: RemoteFloat
    get() = top + height

  fun x(i: Int, n: Int): RemoteFloat = left + width * (i.toFloat() / (n - 1).coerceAtLeast(1)).rf

  fun y(fraction: RemoteFloat): RemoteFloat = top + height * (1f.rf - fraction)
}

/**
 * A sparkline of [values] on the scale [min] .. min + [span], drawn in [family]'s design
 * ([historyTrace], docs/design/everyday-cards.md §2).
 */
internal fun RemoteDrawScope.familySparkline(
  family: HaVisualFamily,
  look: MeterLook,
  values: List<RemoteFloat>,
  min: RemoteFloat,
  span: RemoteFloat,
) {
  if (values.size < 2) return
  historyTrace(family, look, values.map { (it - min) / span })
}

/**
 * One bar per sample of [values] on the scale [base] .. base + [span], drawn in [family]'s design.
 * Classic, Home Assistant and Expressive draw the grammar's bars instead (FamilyCharts).
 */
internal fun RemoteDrawScope.familyBars(
  family: HaVisualFamily,
  look: MeterLook,
  values: List<RemoteFloat>,
  base: RemoteFloat,
  span: RemoteFloat,
) {
  val plot = Plot(this)
  val n = values.size
  if (n == 0) return
  val slot = plot.width / n.toFloat().rf
  val fractions = values.map { (it - base) / span }
  fun x(i: Int, share: Float) = plot.left + slot * i.toFloat().rf + slot * ((1f - share) / 2f).rf
  when (family) {
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
      for (i in 0 until n) {
        communityBar(
          family,
          look,
          fractions[i],
          true,
          canvasWidth = slot * 0.72f.rf,
          canvasHeight = plot.height,
          originX = x(i, 0.72f),
          originY = plot.top,
        )
      }
    }

    // Drawn by the grammar (FamilyCharts).
    HaVisualFamily.HomeAssistant,
    HaVisualFamily.Classic,
    HaVisualFamily.Expressive -> Unit
    // Thin flat bars over a muted scale line.
    HaVisualFamily.Braun -> {
      drawLine(
        strokePaint(1f, look.muted, round = false),
        RemoteOffset(plot.left, plot.bottom),
        RemoteOffset(plot.left + plot.width, plot.bottom),
      )
      for (i in 0 until n) {
        val y = plot.y(fractions[i])
        fillRect(look.value, x(i, 0.3f), y, slot * 0.3f.rf, plot.bottom - y)
      }
    }
    // Stacked LED cells (Trinitron) or chunky segments with ghosts (LCD).
    HaVisualFamily.Trinitron,
    HaVisualFamily.Lcd -> {
      val cells = if (family == HaVisualFamily.Lcd) 6 else 10
      val pitch = plot.height / cells.toFloat().rf
      val cell = pitch * (if (family == HaVisualFamily.Lcd) 0.78f else 0.6f).rf
      val share = if (family == HaVisualFamily.Lcd) 0.72f else 0.6f
      for (i in 0 until n) {
        for (c in 0 until cells) {
          fillRect(
            look.segment(fractions[i], c, cells, HaMeterZones()),
            x(i, share),
            plot.bottom - pitch * (c + 1).toFloat().rf + (pitch - cell),
            slot * share.rf,
            cell,
          )
        }
      }
    }
    // Glowing columns.
    HaVisualFamily.Nixie -> {
      for (i in 0 until n) {
        val y = plot.y(fractions[i])
        look.glow?.let { fillRect(it, x(i, 0.6f), y, slot * 0.6f.rf, plot.bottom - y) }
        fillRect(look.value, x(i, 0.25f), y, slot * 0.25f.rf, plot.bottom - y)
      }
    }
    HaVisualFamily.Ikea -> {
      // Shelf-like columns: solid value, pale side rail and a fine top highlight.
      for (i in 0 until n) {
        val y = plot.y(fractions[i])
        val w = slot * 0.65f.rf
        fillRect(look.value, x(i, 0.65f), y, w, plot.bottom - y)
        fillRect(look.track, x(i, 0.65f), y, w * 0.12f.rf, plot.bottom - y)
      }
    }
    // Lamp-lit columns in a recessed screen.
    HaVisualFamily.Console -> {
      fillRound(ConsoleSlot.rc, 0f.rf, 0f.rf, width, height, 3f.rf)
      for (i in 0 until n) {
        val y = plot.y(fractions[i])
        val w = slot * 0.6f.rf
        fillRound(look.value, x(i, 0.6f), y, w, plot.bottom - y, 1.5f.rf)
      }
    }
  }
}
