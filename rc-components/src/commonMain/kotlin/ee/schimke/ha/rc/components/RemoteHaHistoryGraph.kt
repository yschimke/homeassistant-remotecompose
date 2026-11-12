@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteCanvas
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteTextUnit
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow

/**
 * HA `history-graph` card — title, range label, then one sparkline row per entity.
 *
 * ```
 *   ┌─────────────────────────────────────────────┐
 *   │  Temperature                                │
 *   │  Last 24 hours                              │
 *   │  Outside                          8.2 °C    │
 *   │  ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲    │
 *   │  Cellar                          no data    │   ← an empty row is one line
 *   └─────────────────────────────────────────────┘
 * ```
 *
 * With [singleEmptyState] (the `history-graph` card), when no row has a line the card shows one
 * empty state instead of a row per series: "No history in the last 2 days" and the series' names on
 * a line below it ([showsSingleEmptyState]). It is off by default: the sensor card and the
 * statistics-graph line mode reuse this layout, and their rows must keep their current value when
 * there is no history.
 *
 * [compactEmptyState] is for launcher and Wear cells, whose height is fixed: the whole empty card
 * is one line, the title (else the series' names) and a short "No history" caption beside it, the
 * same shape as an empty row.
 *
 * [plotHeightDp] is each row's trace height. The `history-graph` card draws
 * [HISTORY_PLOT_HEIGHT_DP] so a family's framed design (docs/design/everyday-cards.md §2) reads;
 * the sensor card and the statistics-graph line mode keep the compact [SPARKLINE_HEIGHT_DP] under
 * their larger value.
 *
 * Sparkline is captured from numeric `HistoryPoint`s. Each sample is bound to
 * `<entityId>.numeric.<index>`, so a host can push a sliding window of values without re-encoding
 * the document. The y-axis range is captured from the initial values; samples that drift well
 * outside that range clip to the canvas (host re-encodes when that happens).
 */
@Composable
@RemoteComposable
fun RemoteHaHistoryGraph(
  data: HaHistoryGraphData,
  modifier: RemoteModifier = RemoteModifier,
  singleEmptyState: Boolean = false,
  compactEmptyState: Boolean = false,
  plotHeightDp: Int = SPARKLINE_HEIGHT_DP,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 10.rdp),
  ) {
    val empty = data.showsSingleEmptyState(singleEmptyState)
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(6.rdp)) {
      if (empty && compactEmptyState) {
        CompactEmptyState(data, theme)
        return@RemoteColumn
      }
      if (data.title != null) {
        RemoteText(
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = RemoteFontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
        )
      }
      if (empty) {
        EmptyState(data, theme)
      } else {
        RemoteText(
          text = cardText("range", data.rangeLabel),
          color = theme.secondaryText,
          fontSize = HaType.Caption,
          style = haTextStyle(),
        )
        if (data.rows.isEmpty()) {
          if (haLayouts().notes == HaNoteStyle.Tape)
            ConsoleTapeLabel("No entities".rs, compact = true)
          else
            RemoteText(
              text = "No entities".rs,
              color = theme.secondaryText,
              fontSize = HaType.Body,
              style = haTextStyle(),
            )
        } else {
          data.rows.forEach { row -> Row(row, theme, plotHeightDp) }
        }
      }
    }
  }
}

/**
 * The hero tier of a sensor card with a line to draw, for a launcher widget with room to spare: the
 * name, the value at [HaHeroScale.state], the range, and the sparkline at [plotHeightDp] — the
 * converter's share of the widget's height — so a taller widget gets a taller line, not more blank
 * card under a small one.
 */
@Composable
@RemoteComposable
fun RemoteHaSensorHero(
  data: HaSensorCardData,
  scale: HaHeroScale,
  plotHeightDp: Int,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxSize()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 10.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(HaSpace.S.rdp),
    ) {
      RemoteText(
        text = entityName(data.entityId, data.name),
        color = theme.secondaryText,
        fontSize = HaType.Body,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      RemoteText(
        text = LiveValues.state(data.entityId, data.valueLabel),
        color = theme.primaryText,
        fontSize = scale.state,
        fontWeight = haValueWeight(),
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      if (data.rangeLabel != null) {
        RemoteText(
          text = cardText("range", data.rangeLabel),
          color = theme.secondaryText,
          fontSize = HaType.Caption,
          style = haTextStyle(),
          maxLines = 1,
        )
      }
      if (data.points.size >= 2) {
        Sparkline(
          data.entityId,
          data.points,
          data.accent,
          theme,
          plotHeightDp.coerceAtLeast(SPARKLINE_HEIGHT_DP),
        )
      }
    }
  }
}

/** What [RemoteHaSensorHero] spends on everything but its line, for a converter sizing the line. */
const val SensorHeroChromeDp = 116

/**
 * Identity tier for the `history-graph` family — the smallest cell that still says "this is a
 * trend": the first series with a line, its sparkline + summary, name above. With no line at all it
 * shows the card's empty state message instead. Drops the per-row list and the range chrome (P4/P5)
 * but keeps the P1 identity (spark + value). Used by the Fixed-mode converter at narrow launcher /
 * Wear cells; see docs/architecture/adaptive-card-layouts.md §"Bulk / time-series".
 */
@Composable
@RemoteComposable
fun RemoteHaHistoryGraphIdentity(
  data: HaHistoryGraphData,
  modifier: RemoteModifier = RemoteModifier,
  valueSize: RemoteTextUnit? = HaType.Value,
  plotHeightDp: Int = SPARKLINE_HEIGHT_DP,
) {
  val theme = haTheme()
  val row = if (data.allSeriesEmpty) null else data.rows.firstOrNull { it.hasLine }
  RemoteBox(
    modifier =
      modifier
        .fillMaxSize()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 10.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
    ) {
      RemoteText(
        text =
          if (row != null) entityName(row.entityId, row.name) else (data.title ?: "History").rs,
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      if (row != null) {
        // A launcher cell too narrow for the summary at value size sets it smaller, or (one column)
        // leaves it to the line: a value is never cut short.
        if (valueSize != null)
          RemoteText(
            text = row.summary,
            color = theme.primaryText,
            fontSize = valueSize,
            fontWeight = haValueWeight(),
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
        if (row.points.size >= 2) {
          Sparkline(row.entityId, row.points, row.accent, theme, plotHeightDp)
        }
      } else {
        // The cell's height is fixed and small: a short caption, like the tile's compact name +
        // value, never a wrapped sentence.
        if (haLayouts().notes == HaNoteStyle.Tape)
          ConsoleTapeLabel(
            (if (data.rows.isEmpty()) "No entities" else COMPACT_EMPTY_LABEL).rs,
            compact = true,
          )
        else
          RemoteText(
            text = (if (data.rows.isEmpty()) "No entities" else COMPACT_EMPTY_LABEL).rs,
            color = theme.primaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
      }
    }
  }
}

/**
 * The card-level empty state: one message for the whole window, then every series' name on one line
 * as the legend of what would be drawn. Replaces a "no data" stub per row, which took two lines
 * each.
 */
@Composable
private fun EmptyState(data: HaHistoryGraphData, theme: RemoteHaTheme) {
  if (haLayouts().notes == HaNoteStyle.Tape) ConsoleTapeLabel(cardText("empty", data.emptyLabel))
  else
    RemoteText(
      text = cardText("empty", data.emptyLabel),
      color = theme.secondaryText,
      fontSize = HaType.Body,
      style = haTextStyle(),
      maxLines = 2,
      overflow = RemoteTextOverflow.Ellipsis,
    )
  RemoteText(
    text = legend(data),
    color = theme.primaryText,
    fontSize = HaType.Caption,
    style = haTextStyle(),
    maxLines = 1,
    overflow = RemoteTextOverflow.Ellipsis,
  )
}

/**
 * The empty state in a fixed-height launcher or Wear cell: one line, the title (else the series'
 * names) at row-label size with a short "No history" caption beside it — the same shape as an empty
 * row, so it fits a Wear small slot's ~44 dp.
 */
@Composable
private fun CompactEmptyState(data: HaHistoryGraphData, theme: RemoteHaTheme) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.SpaceBetween,
  ) {
    RemoteText(
      text = if (data.title != null) cardTitle(data.title) else legend(data),
      color = theme.primaryText,
      fontSize = HaType.Label,
      fontWeight = RemoteFontWeight.Medium,
      style = haTextStyle(),
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
      modifier = RemoteModifier.weight(1f.rf).padding(end = 8.rdp),
    )
    if (haLayouts().notes == HaNoteStyle.Tape)
      ConsoleTapeLabel(COMPACT_EMPTY_LABEL.rs, compact = true)
    else
      RemoteText(
        text = COMPACT_EMPTY_LABEL.rs,
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
      )
  }
}

@Composable
private fun legend(data: HaHistoryGraphData) =
  data.rows
    .map { entityName(it.entityId, it.name) }
    .reduce { line, name -> line + LEGEND_SEPARATOR + name }

/** The empty state's short form, where the full "No history in the last …" can't fit. */
const val COMPACT_EMPTY_LABEL: String = "No history"

private const val LEGEND_SEPARATOR = "  ·  "

@Composable
private fun Row(row: HaHistoryGraphRow, theme: RemoteHaTheme, plotHeightDp: Int) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.SpaceBetween,
  ) {
    RemoteText(
      text = entityName(row.entityId, row.name),
      color = theme.primaryText,
      fontSize = HaType.Label,
      fontWeight = RemoteFontWeight.Medium,
      style = haTextStyle(),
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
      modifier = RemoteModifier.weight(1f.rf).padding(end = 8.rdp),
    )
    RemoteText(
      text = row.summary,
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
    )
  }
  // An empty row stays one line, its name and "no data": no second stub line under it.
  if (row.hasLine) Sparkline(row.entityId, row.points, row.accent, theme, plotHeightDp)
}

@Composable
private fun Sparkline(
  entityId: String?,
  points: List<Float>,
  accent: Color,
  theme: RemoteHaTheme,
  heightDp: Int,
) {
  // A fixed number of samples, one named RemoteFloat each (`<entityId>.numeric.<i>`), so the
  // series length is never part of the document's structure and a host can push the next window
  // without re-encoding. The y-range is `<entityId>.series_min` / `series_span`: constants in a
  // live capture, bindings in a design template.
  val samples = sparklineSamples(points)
  val range = sparklineRange(samples)
  val bound: List<RemoteFloat> = LiveValues.numericPoints(entityId, samples)
  val minRf = entityFloat(entityId, "series_min", range.min)
  val spanRf = entityFloat(entityId, "series_span", range.span)
  // The row's design is the uber theme's family (Charts.kt); its colour the theme's ink, else the
  // row's accent.
  val look = meterLook(accent.rc, theme.divider)
  val charts = chartRenderer()
  val grammar = haGrammar()
  RemoteCanvas(modifier = RemoteModifier.fillMaxWidth().height(heightDp.rdp)) {
    with(charts) { sparkline(look, grammar, bound, minRf, spanRf) }
  }
}

/** A sparkline row's compact trace height: the sensor card, statistics graph and identity tier. */
const val SPARKLINE_HEIGHT_DP: Int = 28

/** The `history-graph` card's trace height, tall enough for each family's framed design. */
const val HISTORY_PLOT_HEIGHT_DP: Int = 40

/** How many samples every sparkline draws, whatever the history length. */
const val SPARKLINE_SAMPLES: Int = 48

/**
 * [points] resampled to [SPARKLINE_SAMPLES] by linear interpolation over their order, so the drawn
 * line has the same shape whatever the history length. Fewer than two points have no line: empty.
 */
fun sparklineSamples(points: List<Float>): List<Float> {
  if (points.size < 2) return emptyList()
  val last = points.size - 1
  return List(SPARKLINE_SAMPLES) { i ->
    val pos = i.toFloat() * last / (SPARKLINE_SAMPLES - 1)
    val lo = pos.toInt().coerceAtMost(last - 1)
    val t = pos - lo
    points[lo] + (points[lo + 1] - points[lo]) * t
  }
}

/** A sparkline's y-range: its lowest sample and the span above it (1 for a flat line). */
data class SparklineRange(val min: Float, val span: Float)

fun sparklineRange(samples: List<Float>): SparklineRange {
  if (samples.isEmpty()) return SparklineRange(0f, 1f)
  val min = samples.min()
  return SparklineRange(min, (samples.max() - min).takeIf { it > 0f } ?: 1f)
}
