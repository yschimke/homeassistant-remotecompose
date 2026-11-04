@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteDrawScope
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf

// ---------------------------------------------------------------------------------------------
// Graph rows as a slot (HaComponentSlot.Charts): a history sparkline and a statistics bar row. A
// theme's own charts win; a bespoke family's are its traces in HistoryTraces.kt and bars in
// Charts.kt; a kit's are these, drawn from its grammar. Every sample is a live RemoteFloat, and a
// lit cell is a layer sized by a float switch, so a launcher widget can play every one.
// ---------------------------------------------------------------------------------------------

/**
 * Draws a style's graph rows: live [values] on the scale `min .. min + span`, in the look's
 * colours.
 */
internal interface HaChartRenderer : HaSlotRenderer {
  fun RemoteDrawScope.sparkline(
    look: MeterLook,
    grammar: HaComponentGrammar,
    values: List<RemoteFloat>,
    min: RemoteFloat,
    span: RemoteFloat,
  )

  fun RemoteDrawScope.bars(
    look: MeterLook,
    grammar: HaComponentGrammar,
    values: List<RemoteFloat>,
    base: RemoteFloat,
    span: RemoteFloat,
  )
}

/** The graph rows the active theme draws: its own, else its family's. */
@androidx.compose.runtime.Composable
internal fun chartRenderer(): HaChartRenderer {
  val theme = currentHaUberTheme().theme
  return theme.components.charts ?: FamilyCharts.of(theme.visuals)
}

/**
 * A bespoke family's graph rows: its traces (HistoryTraces.kt) and bars (Charts.kt) where it has
 * its own, else the grammar's ([GrammarCharts]) in its tokens — the cards' own line and bars are
 * those.
 */
internal class FamilyCharts private constructor(private val family: HaVisualFamily) :
  HaChartRenderer {
  private val grammarTrace =
    family == HaVisualFamily.Classic || family == HaVisualFamily.HomeAssistant
  private val grammarBars = grammarTrace || family == HaVisualFamily.Expressive

  override fun RemoteDrawScope.sparkline(
    look: MeterLook,
    grammar: HaComponentGrammar,
    values: List<RemoteFloat>,
    min: RemoteFloat,
    span: RemoteFloat,
  ) =
    if (grammarTrace) with(GrammarCharts) { sparkline(look, grammar, values, min, span) }
    else familySparkline(family, look, values, min, span)

  override fun RemoteDrawScope.bars(
    look: MeterLook,
    grammar: HaComponentGrammar,
    values: List<RemoteFloat>,
    base: RemoteFloat,
    span: RemoteFloat,
  ) =
    if (grammarBars) with(GrammarCharts) { bars(look, grammar, values, base, span) }
    else familyBars(family, look, values, base, span)

  companion object {
    private val byFamily = HaVisualFamily.entries.associateWith { FamilyCharts(it) }

    fun of(family: HaVisualFamily): FamilyCharts = byFamily.getValue(family)
  }
}

/**
 * Graph rows from the grammar:
 * - **continuous**: a line at the grammar's weight over a baseline, with a halo where the grammar
 *   glows or has a gradient, and the newest sample dotted where it has a stop dot; bars round- or
 *   flat-topped by its caps.
 * - **segmented**: columns of cells with their ghosts, lit to each sample.
 * - **pointer**: a hairline trace over index ticks, the newest sample a dot; thin bars.
 */
internal object GrammarCharts : HaChartRenderer {
  override fun RemoteDrawScope.sparkline(
    look: MeterLook,
    grammar: HaComponentGrammar,
    values: List<RemoteFloat>,
    min: RemoteFloat,
    span: RemoteFloat,
  ) {
    if (values.size < 2) return
    val fractions = values.map { (it - min) / span }
    val w = (2f * grammar.stroke.scale).coerceAtLeast(1f)
    when (grammar.level) {
      HaLevelStyle.Segmented -> segmentColumns(look, grammar, fractions, cells = 6)
      HaLevelStyle.Pointer -> {
        val p = Plot(this, bottom = 6f)
        p.baseline(this, look.track)
        val ticks = grammar.ticks.takeIf { it > 0 } ?: 10
        for (i in 0..ticks) {
          val x = p.left + p.width * (i.toFloat() / ticks).rf
          drawLine(
            strokePaint(1f, look.muted, round = false),
            RemoteOffset(x, p.bottom),
            RemoteOffset(x, p.bottom + 3f.rf),
          )
        }
        p.line(this, fractions, 1f, look.value, grammar.roundCaps)
        drawCircle(fillPaint(look.value), 2.5f.rf, p.last(fractions))
      }
      HaLevelStyle.Continuous -> {
        val p = Plot(this)
        p.baseline(this, look.track)
        val spec = HaVisualSpec(look, 0f.rf, grammar)
        val halo =
          spec.gradient?.first()?.copy(alpha = 0.35f)?.let {
            androidx.compose.remote.creation.compose.state.RemoteColor(it)
          } ?: look.glow?.takeIf { grammar.glow }
        halo?.let { p.line(this, fractions, w * 3f, it, round = true) }
        p.line(this, fractions, w, look.value, grammar.roundCaps)
        if (grammar.stopDot) drawCircle(fillPaint(look.value), (w * 1.2f).rf, p.last(fractions))
      }
    }
  }

  override fun RemoteDrawScope.bars(
    look: MeterLook,
    grammar: HaComponentGrammar,
    values: List<RemoteFloat>,
    base: RemoteFloat,
    span: RemoteFloat,
  ) {
    val fractions = values.map { (it - base) / span }
    val n = fractions.size
    if (n == 0) return
    if (grammar.level == HaLevelStyle.Segmented) {
      segmentColumns(look, grammar, fractions, cells = 6)
      return
    }
    val plot = Plot(this)
    val slot = plot.width / n.toFloat().rf
    val share = if (grammar.level == HaLevelStyle.Pointer) 0.3f else 0.7f
    drawLine(
      strokePaint(
        1f,
        if (grammar.level == HaLevelStyle.Pointer) look.muted else look.track,
        round = false,
      ),
      RemoteOffset(plot.left, plot.bottom),
      RemoteOffset(plot.left + plot.width, plot.bottom),
    )
    for (i in 0 until n) {
      val y = plot.y(fractions[i])
      val x = plot.left + slot * i.toFloat().rf + slot * ((1f - share) / 2f).rf
      val bw = slot * share.rf
      val r = if (grammar.roundCaps) bw / 2f.rf else bw * 0.12f.rf
      fillRound(look.value, x, y, bw, plot.bottom - y, r.min(plot.bottom - y))
    }
  }

  /** A column of [cells] per sample, ghosted, lit up to the sample as layers. */
  private fun RemoteDrawScope.segmentColumns(
    look: MeterLook,
    grammar: HaComponentGrammar,
    fractions: List<RemoteFloat>,
    cells: Int,
  ) {
    val p = Plot(this)
    val n = fractions.size
    val slot = p.width / n.toFloat().rf
    val bar = slot * 0.72f.rf
    val pitch = p.height / cells.toFloat().rf
    val cell = pitch * 0.74f.rf
    for (i in 0 until n) {
      val x = p.left + slot * i.toFloat().rf + (slot - bar) / 2f.rf
      val spec = HaVisualSpec(look, fractions[i], grammar)
      for (k in 0 until cells) {
        val y = p.bottom - pitch * (k + 1).toFloat().rf + (pitch - cell)
        fillRect(look.ghost, x, y, bar, cell)
        for ((color, on) in spec.segmentLayers(k, cells)) {
          fillRect(color, x, y + cell * (1f.rf - on), bar, cell * on)
        }
      }
    }
  }
}
