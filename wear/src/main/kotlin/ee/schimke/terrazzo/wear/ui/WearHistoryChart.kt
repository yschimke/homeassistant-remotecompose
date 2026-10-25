package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.ha.model.HistoryPoint
import kotlin.time.Instant

/**
 * One entity's history as the details screen draws it, between a window's start and end: a [Line]
 * for a numeric state (a temperature), a [Timeline] of state runs for anything else (a light's on
 * and off), or [Empty] when there is nothing to draw.
 */
internal sealed interface HistoryChart {
  /** Numeric values at [points]' x (the fraction of the window, 0 to 1), from [min] to [max]. */
  data class Line(val points: List<Pair<Float, Float>>, val min: Float, val max: Float) :
    HistoryChart

  /** The runs of each state across the window, in order. */
  data class Timeline(val segments: List<Segment>) : HistoryChart

  /** [state] from fraction [from] to fraction [to] of the window. */
  data class Segment(val from: Float, val to: Float, val state: String)

  data object Empty : HistoryChart
}

/** States that mean "no value" rather than a value: left out of a line, drawn dim on a timeline. */
private val NO_VALUE = setOf("unavailable", "unknown", "")

/**
 * The chart for [points] (oldest first) over [start] to [end]. A line when the entity's values are
 * numbers (at least two, and nothing but numbers and no-value states), each value held until the
 * next and the last one to [end]; otherwise a timeline of its states.
 */
internal fun historyChart(points: List<HistoryPoint>, start: Instant, end: Instant): HistoryChart {
  val span = (end - start).inWholeMilliseconds.toFloat()
  if (points.isEmpty() || span <= 0f) return HistoryChart.Empty
  fun x(at: Instant) = ((at - start).inWholeMilliseconds / span).coerceIn(0f, 1f)
  val numeric = points.filter { it.state !in NO_VALUE }
  val values = numeric.mapNotNull { it.state.toFloatOrNull() }
  if (values.size >= 2 && values.size == numeric.size) {
    // A step line: each value holds until the next one arrives.
    val steps = buildList {
      numeric.forEachIndexed { i, point ->
        val value = values[i]
        if (i > 0) add(x(point.ts) to values[i - 1])
        add(x(point.ts) to value)
      }
      add(1f to values.last())
    }
    return HistoryChart.Line(steps, values.min(), values.max())
  }
  val segments =
    points.indices
      .map { i ->
        val to = points.getOrNull(i + 1)?.ts?.let(::x) ?: 1f
        HistoryChart.Segment(x(points[i].ts), to, points[i].state)
      }
      .filter { it.to > it.from }
  return if (segments.isEmpty()) HistoryChart.Empty else HistoryChart.Timeline(segments)
}

/** States drawn as "active" on a timeline: the colour of a light that is on. */
private val ACTIVE =
  setOf("on", "open", "opening", "unlocked", "home", "playing", "heat", "cool", "detected")

/** [chart] across the screen, with its range or its window's ends underneath. */
@Composable
internal fun WearHistoryChart(chart: HistoryChart, unit: String, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth()) {
    when (chart) {
      is HistoryChart.Line -> {
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
          val range = (chart.max - chart.min).takeIf { it > 0f } ?: 1f
          fun at(point: Pair<Float, Float>) =
            Offset(
              point.first * size.width,
              size.height - (point.second - chart.min) / range * size.height,
            )
          val path = Path()
          chart.points.forEachIndexed { i, point ->
            val o = at(point)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
          }
          drawPath(
            path,
            colors.primary,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
          )
        }
        ChartLegend(
          start = "${format(chart.min)} $unit".trim(),
          end = "${format(chart.max)} $unit".trim(),
        )
      }
      is HistoryChart.Timeline -> {
        Canvas(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
          chart.segments.forEach { segment ->
            val color =
              when (segment.state) {
                in ACTIVE -> colors.primary
                in NO_VALUE -> colors.outlineVariant
                else -> colors.surfaceContainerHigh
              }
            drawRect(
              color,
              topLeft = Offset(segment.from * size.width, 0f),
              size = Size((segment.to - segment.from) * size.width, size.height),
            )
          }
        }
        ChartLegend(start = "24 h ago", end = "Now")
      }
      HistoryChart.Empty ->
        Text(
          "No history in the last 24 hours",
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
        )
    }
  }
}

@Composable
private fun ChartLegend(start: String, end: String) {
  val style = MaterialTheme.typography.labelSmall
  val color = MaterialTheme.colorScheme.onSurfaceVariant
  Row(Modifier.fillMaxWidth()) {
    Text(start, style = style, color = color)
    Spacer(Modifier.weight(1f))
    Text(end, style = style, color = color)
  }
}

private fun format(value: Float): String =
  if (value == value.toLong().toFloat()) value.toLong().toString()
  else ((value * 10).toLong() / 10f).toString()
