@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * HA `history-graph` card — title, range label, then one sparkline row per entity.
 *
 * ```
 *   ┌─────────────────────────────────────────────┐
 *   │  Temperature                                │
 *   │  Last 24h                                   │
 *   │  Outside        ╱╲╱╲╱╲╱╲╱╲╱╲╱╲   8.2 °C    │
 *   │  Upstairs       ─────~~~~~~──   22.2 °C    │
 *   └─────────────────────────────────────────────┘
 * ```
 *
 * Sparkline is captured from numeric `HistoryPoint`s. Each sample is bound to
 * `<entityId>.numeric.<index>`, so a host can push a sliding window of values without re-encoding
 * the document. The y-axis range is captured from the initial values; samples that drift well
 * outside that range clip to the canvas (host re-encodes when that happens).
 */
@Composable
@RemoteComposable
fun RemoteHaHistoryGraph(data: HaHistoryGraphData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 10.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(6.rdp)) {
      if (data.title != null) {
        RemoteText(
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      RemoteText(
        text = cardText("range", data.rangeLabel),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
      )
      if (data.rows.isEmpty()) {
        RemoteText(
          text = "No entities".rs,
          color = theme.secondaryText,
          fontSize = HaType.Body,
          style = haTextStyle(),
        )
      } else {
        data.rows.forEach { row -> Row(row, theme) }
      }
    }
  }
}

/**
 * Identity tier for the `history-graph` family — the smallest cell that still says "this is a
 * trend": the first series' sparkline + its latest value, name above. Drops the per-row list and
 * the range chrome (P4/P5) but keeps the P1 identity (spark + value). Used by the Fixed-mode
 * converter at narrow launcher / Wear cells; see docs/architecture/adaptive-card-layouts.md §"Bulk
 * / time-series".
 */
@Composable
@RemoteComposable
fun RemoteHaHistoryGraphIdentity(
  data: HaHistoryGraphData,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  val row = data.rows.firstOrNull()
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
        overflow = TextOverflow.Ellipsis,
      )
      if (row != null) {
        RemoteText(
          text = row.summary,
          color = theme.primaryText,
          fontSize = HaType.Value,
          fontWeight = haValueWeight(),
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (row.points.size >= 2) {
          Sparkline(row.entityId, row.points, row.accent, theme)
        }
      } else {
        RemoteText(
          text = "No data".rs,
          color = theme.secondaryText,
          fontSize = HaType.Body,
          style = haTextStyle(),
        )
      }
    }
  }
}

@Composable
private fun Row(row: HaHistoryGraphRow, theme: RemoteHaTheme) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.SpaceBetween,
  ) {
    RemoteText(
      text = entityName(row.entityId, row.name),
      color = theme.primaryText,
      fontSize = HaType.Label,
      fontWeight = FontWeight.Medium,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = RemoteModifier.weight(1f).padding(end = 8.rdp),
    )
    RemoteText(
      text = row.summary,
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
    )
  }
  if (row.points.size >= 2) {
    Sparkline(row.entityId, row.points, row.accent, theme)
  } else {
    RemoteText(
      text = "No samples".rs,
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
    )
  }
}

@Composable
private fun Sparkline(entityId: String?, points: List<Float>, accent: Color, theme: RemoteHaTheme) {
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
  val family = visualFamily()
  RemoteCanvas(modifier = RemoteModifier.fillMaxWidth().height(28.rdp)) {
    familySparkline(family, look, bound, minRf, spanRf)
  }
}

private fun Color.toArgb(): Int =
  android.graphics.Color.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
  )

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
