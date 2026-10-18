@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf

// ---------------------------------------------------------------------------------------------
// Graph rows (history-graph sparklines, statistics-graph bars) in each visual family. Like the
// meters, a family is a different *design*: an LCD history is a bar graph of segments with its
// ghosts, a Nixie line is a glowing filament, a Console graph sits in a recessed screen. Every
// sample is a live RemoteFloat, so each design moves with the pushed window like the classic line.
// ---------------------------------------------------------------------------------------------

/** The plot area of a graph row canvas: a small inset so strokes and dots aren't clipped. */
private class Plot(scope: RemoteDrawScope) {
  val left: RemoteFloat = 2f.rf
  val top: RemoteFloat = 3f.rf
  val width: RemoteFloat = scope.width - 4f.rf
  val height: RemoteFloat = scope.height - 6f.rf
  val bottom: RemoteFloat
    get() = top + height

  fun x(i: Int, n: Int): RemoteFloat = left + width * (i.toFloat() / (n - 1).coerceAtLeast(1)).rf

  fun y(fraction: RemoteFloat): RemoteFloat = top + height * (1f.rf - fraction)
}

/**
 * A sparkline of [values] on the scale [min] .. min + [span], drawn in [family]'s design. Classic
 * is a 2px round line over a hairline baseline.
 */
internal fun RemoteDrawScope.familySparkline(
  family: HaVisualFamily,
  look: MeterLook,
  values: List<RemoteFloat>,
  min: RemoteFloat,
  span: RemoteFloat,
) {
  val plot = Plot(this)
  val n = values.size
  if (n < 2) return
  val fractions = values.map { (it - min) / span }
  fun line(widthPx: Float, color: RemoteColor) {
    val paint = strokePaint(widthPx, color)
    for (i in 0 until n - 1) {
      drawLine(
        paint,
        RemoteOffset(plot.x(i, n), plot.y(fractions[i])),
        RemoteOffset(plot.x(i + 1, n), plot.y(fractions[i + 1])),
      )
    }
  }
  fun baseline(color: RemoteColor) =
    drawLine(
      strokePaint(1f, color, round = false),
      RemoteOffset(plot.left, plot.bottom),
      RemoteOffset(plot.left + plot.width, plot.bottom),
    )
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
    HaVisualFamily.Aeon -> communitySparkline(family, look, fractions)

    HaVisualFamily.HomeAssistant,
    HaVisualFamily.Classic -> {
      baseline(look.track)
      line(2f, look.value)
    }
    // A soft area in the track tone under a thick round line.
    HaVisualFamily.Expressive -> {
      val slot = plot.width / (n - 1).toFloat().rf
      for (i in 0 until n) {
        val y = plot.y(fractions[i])
        fillRect(look.track, plot.x(i, n) - slot / 2f.rf, y, slot, plot.bottom - y)
      }
      line(4f, look.value)
    }
    // Hairline trace over a dotted graticule, the newest sample marked.
    HaVisualFamily.Trinitron -> {
      for (g in 1..3) {
        val y = plot.top + plot.height * (g / 4f).rf
        for (d in 0 until 40) {
          if (d % 2 == 0) {
            val x0 = plot.left + plot.width * (d / 40f).rf
            fillRect(look.track, x0, y, plot.width / 40f.rf, 1f.rf)
          }
        }
      }
      baseline(look.muted)
      line(1.5f, look.value)
      drawCircle(
        fillPaint(look.value),
        2.5f.rf,
        RemoteOffset(plot.x(n - 1, n), plot.y(fractions.last())),
      )
    }
    // A bar graph of segments: every column's ghost, lit to the sample's height.
    HaVisualFamily.Lcd -> {
      val slot = plot.width / n.toFloat().rf
      val bar = slot * 0.7f.rf
      for (i in 0 until n) {
        val x = plot.left + slot * i.toFloat().rf + (slot - bar) / 2f.rf
        fillRect(look.ghost, x, plot.top, bar, plot.height)
        val y = plot.y(fractions[i])
        fillRect(look.value, x, y, bar, plot.bottom - y)
      }
    }
    // A hairline trace on a hairline baseline; the latest reading an orange dot.
    HaVisualFamily.Braun -> {
      baseline(look.muted)
      line(1.5f, look.value)
      drawCircle(
        fillPaint(look.accent),
        3f.rf,
        RemoteOffset(plot.x(n - 1, n), plot.y(fractions.last())),
      )
    }
    // A glowing filament.
    HaVisualFamily.Nixie -> {
      look.glow?.let { line(6f, it) }
      line(1.5f, look.value)
    }
    HaVisualFamily.Ikea -> {
      for (g in 1..3) {
        fillRect(look.track, plot.left, plot.top + plot.height * (g / 4f).rf, plot.width, 1f.rf)
      }
      baseline(look.muted)
      line(3f, look.value)
      drawCircle(
        fillPaint(look.accent),
        3f.rf,
        RemoteOffset(plot.x(n - 1, n), plot.y(fractions.last())),
      )
    }
    // A trace in a recessed screen with a faint graticule.
    HaVisualFamily.Console -> {
      fillRound(ConsoleSlot.rc, 0f.rf, 0f.rf, width, height, 3f.rf)
      for (g in 1..3) {
        fillRect(
          ConsoleSlotLip.rc,
          plot.left,
          plot.top + plot.height * (g / 4f).rf,
          plot.width,
          1f.rf,
        )
      }
      line(2f, look.value)
    }
  }
}

/**
 * One bar per sample of [values] on the scale [base] .. base + [span], drawn in [family]'s design.
 * Classic is a flat bar 70% of its slot over a hairline baseline.
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

    HaVisualFamily.HomeAssistant,
    HaVisualFamily.Classic,
    HaVisualFamily.Braun -> {
      val share = if (family == HaVisualFamily.Braun) 0.3f else 0.7f
      drawLine(
        strokePaint(
          1f,
          if (family == HaVisualFamily.Braun) look.muted else look.track,
          round = false,
        ),
        RemoteOffset(plot.left, plot.bottom),
        RemoteOffset(plot.left + plot.width, plot.bottom),
      )
      for (i in 0 until n) {
        val y = plot.y(fractions[i])
        fillRect(look.value, x(i, share), y, slot * share.rf, plot.bottom - y)
      }
    }
    // Capsules, round at the top.
    HaVisualFamily.Expressive -> {
      for (i in 0 until n) {
        val y = plot.y(fractions[i])
        val w = slot * 0.7f.rf
        fillRound(look.value, x(i, 0.7f), y, w, plot.bottom - y, w / 2f.rf)
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

/** Each family keeps its scale grammar in charts, without changing the host's history contract. */
private fun RemoteDrawScope.communitySparkline(
  family: HaVisualFamily,
  look: MeterLook,
  fractions: List<RemoteFloat>,
) {
  val p = Plot(this)
  val n = fractions.size
  val lineWidth =
    when (family) {
      HaVisualFamily.Mushroom -> 3f
      HaVisualFamily.Bubble -> 5f
      HaVisualFamily.Cupertino -> 3.5f
      HaVisualFamily.Lcars -> 6f
      else -> 1.5f
    }
  for (i in 0 until n - 1) {
    val a = RemoteOffset(p.x(i, n), p.y(fractions[i]))
    val b = RemoteOffset(p.x(i + 1, n), p.y(fractions[i + 1]))
    when (family) {
      HaVisualFamily.Minimalist,
      HaVisualFamily.Catppuccin,
      HaVisualFamily.Lcars -> {
        drawLine(strokePaint(lineWidth, look.value, false), a, RemoteOffset(b.x, a.y))
        drawLine(strokePaint(lineWidth, look.value, false), RemoteOffset(b.x, a.y), b)
      }
      HaVisualFamily.Noctis -> {
        drawLine(strokePaint(7f, look.track), a, b)
        drawLine(strokePaint(1.5f, look.value), a, b)
      }
      else -> drawLine(strokePaint(lineWidth, look.value), a, b)
    }
  }
  when (family) {
    HaVisualFamily.Mushroom,
    HaVisualFamily.Bubble,
    HaVisualFamily.Catppuccin -> {
      for (i in fractions.indices) drawCircle(
        fillPaint(look.value),
        (if (family == HaVisualFamily.Bubble) 4f else 2.5f).rf,
        RemoteOffset(p.x(i, n), p.y(fractions[i])),
      )
    }
    HaVisualFamily.Cupertino -> {
      drawCircle(
        strokePaint(2f, look.text),
        4f.rf,
        RemoteOffset(p.x(n - 1, n), p.y(fractions.last())),
      )
    }
    HaVisualFamily.Obsidian -> {
      for (i in 0..4) fillRect(look.track, p.left, p.top + p.height * (i / 4f).rf, p.width, 1f.rf)
    }
    HaVisualFamily.Arctic -> {
      fillRect(look.muted, p.left, p.bottom, p.width, 1f.rf)
      drawLine(
        strokePaint(1f, look.accent),
        RemoteOffset(p.x(n - 1, n), p.top),
        RemoteOffset(p.x(n - 1, n), p.bottom),
      )
    }
    HaVisualFamily.Aeon -> {
      outlineRect(look.accent, 0f.rf, 0f.rf, width, height, 1f.rf)
      val at = RemoteOffset(p.x(n - 1, n), p.y(fractions.last()))
      rotate(45f.rf, at) { fillRect(look.value, at.x - 3f.rf, at.y - 3f.rf, 6f.rf, 6f.rf) }
    }
    else -> Unit
  }
}
