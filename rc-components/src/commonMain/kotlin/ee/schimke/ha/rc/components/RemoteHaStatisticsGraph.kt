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
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteSize
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemotePaint
import ee.schimke.composeai.rccreation.compose.state.RemotePaintingStyle
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow

/**
 * `statistics-graph` card. Three visual modes:
 *
 * - line (default) — one sparkline per row, identical to history-graph.
 * - bar — one bar per bucket per row, drawn as filled rects.
 * - stacked bar (only when [HaStatisticsGraphData.stacked]) — a single combined canvas where each
 *   bucket is the sum of all rows, with each row contributing a coloured segment. Rows whose
 *   lengths differ are aligned to the longest series; missing samples count as zero.
 *
 * Stacking only makes sense for non-negative sums (e.g. power per room); negative values render
 * below the baseline at their own offset which matches HA's behaviour.
 */
@Composable
@RemoteComposable
fun RemoteHaStatisticsGraph(
  data: HaStatisticsGraphData,
  modifier: RemoteModifier = RemoteModifier,
  plotHeightDp: Int = SPARKLINE_HEIGHT_DP,
) {
  if (data.chartType != "bar") {
    RemoteHaHistoryGraph(
      HaHistoryGraphData(title = data.title, rangeLabel = data.rangeLabel, rows = data.rows),
      modifier = modifier,
      plotHeightDp = plotHeightDp,
    )
    return
  }

  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 10.rdp),
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(6.rdp)) {
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
      } else if (data.stacked) {
        StackedBars(data.rows, theme, plotHeightDp.coerceAtLeast(STACKED_BARS_HEIGHT_DP))
        data.rows.forEach { row -> Legend(row, theme) }
      } else {
        data.rows.forEach { row -> BarRow(row, theme, plotHeightDp) }
      }
    }
  }
}

@Composable
private fun BarRow(row: HaHistoryGraphRow, theme: RemoteHaTheme, plotHeightDp: Int) {
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
  if (row.points.isEmpty()) {
    if (haLayouts().notes == HaNoteStyle.Tape) ConsoleTapeLabel("No samples".rs, compact = true)
    else
      RemoteText(
        text = "No samples".rs,
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
      )
  } else {
    Bars(row.entityId, row.points, row.accent, theme, plotHeightDp)
  }
}

@Composable
private fun Legend(row: HaHistoryGraphRow, theme: RemoteHaTheme) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.SpaceBetween,
  ) {
    RemoteRow(
      verticalAlignment = RemoteAlignment.CenterVertically,
      modifier = RemoteModifier.weight(1f.rf).padding(end = 8.rdp),
    ) {
      RemoteBox(
        modifier =
          RemoteModifier.height(10.rdp)
            .clip(RemoteRoundedCornerShape(2.rdp))
            .background(row.accent.rc)
            .padding(horizontal = 5.rdp),
      ) {}
      RemoteText(
        text = entityName(row.entityId, row.name),
        color = theme.primaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
        modifier = RemoteModifier.weight(1f.rf).padding(start = 6.rdp),
      )
    }
    RemoteText(
      text = row.summary,
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
    )
  }
}

@Composable
private fun Bars(
  entityId: String?,
  points: List<Float>,
  accent: Color,
  theme: RemoteHaTheme,
  heightDp: Int,
) {
  // One bar per bucket; the count is structure, every value and the axis are
  // `<entityId>.bar.<i>` / `bar_base` / `bar_span` — constants in a live capture,
  // bindings in a design template.
  val range = barRange(points)
  val base = entityFloat(entityId, "bar_base", range.min)
  val span = entityFloat(entityId, "bar_span", range.span)
  val values = points.mapIndexed { i, v -> entityFloat(entityId, "bar.$i", v) }
  val look = meterLook(accent.rc, theme.divider)
  val charts = chartRenderer()
  val grammar = haGrammar()
  RemoteCanvas(modifier = RemoteModifier.fillMaxWidth().height(heightDp.rdp)) {
    with(charts) { bars(look, grammar, values, base, span) }
  }
}

/** The stacked chart's least height: one canvas carries every row. */
private const val STACKED_BARS_HEIGHT_DP = 64

@Composable
private fun StackedBars(rows: List<HaHistoryGraphRow>, theme: RemoteHaTheme, heightDp: Int) {
  // A monochrome theme prints every row in its ink, told apart by strength.
  val ink = currentHaUberTheme().ink
  val rowColors = rows.mapIndexed { r, row ->
    ink?.copy(alpha = 1f - 0.6f * r / rows.size.coerceAtLeast(1)) ?: row.accent
  }
  val n = rows.maxOf { it.points.size }
  // Each segment is `<entityId>.stack.<i>` (its value clamped at zero) and the axis
  // `card.stack_max`; a zero segment draws nothing, in-document.
  val maxTotal = cardFloat("stack_max", stackMax(rows.map { it.points }))
  val segments = rows.map { row ->
    List(n) { i -> entityFloat(row.entityId, "stack.$i", stackValue(row.points, i)) }
  }

  RemoteCanvas(modifier = RemoteModifier.fillMaxWidth().height(heightDp.rdp)) {
    val w = width
    val h = height
    val padX = 2f.rf
    val padY = 3f.rf
    val drawW = w - padX * 2f.rf
    val drawH = h - padY * 2f.rf

    val baseline = RemotePaint {
      isAntiAlias = true
      style = RemotePaintingStyle.Stroke
      strokeWidth = 1f.rf
    }
      .apply { color = theme.divider }
    drawLine(baseline, RemoteOffset(padX, padY + drawH), RemoteOffset(padX + drawW, padY + drawH))

    val slot = drawW / n.toFloat().rf
    val barWidth = slot * 0.75f.rf
    val gap = (slot - barWidth) * 0.5f.rf

    for (i in 0 until n) {
      val x0 = padX + slot * i.toFloat().rf + gap
      var cumulative: RemoteFloat = 0f.rf
      rows.forEachIndexed { r, row ->
        val v = segments[r][i]
        val segTop = padY + drawH * (1f.rf - (cumulative + v) / maxTotal)
        val segHeight = drawH * (v / maxTotal)
        val fill = RemotePaint {
          isAntiAlias = true
          style = RemotePaintingStyle.Fill
          color = rowColors[r].rc
        }
        drawRect(fill, RemoteOffset(x0, segTop), RemoteSize(barWidth, segHeight))
        cumulative += v
      }
    }
  }
}

/**
 * A bar row's axis: anchored at zero when every value is non-negative, otherwise at the lowest so
 * negative-only series still fill the canvas; the span above it (1 when flat).
 */
fun barRange(points: List<Float>): SparklineRange {
  if (points.isEmpty()) return SparklineRange(0f, 1f)
  val minP = points.min()
  val base = if (minP >= 0f) 0f else minP
  return SparklineRange(base, (points.max() - base).takeIf { it > 0f } ?: 1f)
}

/** Bucket [i] of a stacked series: its value clamped at zero, 0 when the row has no such bucket. */
fun stackValue(points: List<Float>, i: Int): Float = (points.getOrNull(i) ?: 0f).coerceAtLeast(0f)

/** The tallest stack across [rows]' buckets (1 when every bucket is empty). */
fun stackMax(rows: List<List<Float>>): Float {
  val n = rows.maxOfOrNull { it.size } ?: 0
  return (0 until n)
    .maxOfOrNull { i -> rows.sumOf { stackValue(it, i).toDouble() }.toFloat() }
    ?.takeIf { it > 0f } ?: 1f
}
