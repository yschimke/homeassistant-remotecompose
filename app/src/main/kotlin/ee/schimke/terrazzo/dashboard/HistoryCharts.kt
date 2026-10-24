package ee.schimke.terrazzo.dashboard

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.ha.model.HistoryPoint
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Line chart with axes, mirroring Home Assistant's own more-info history graph: the unit sits at
 * the top-left, "nice"-rounded value gridlines run across with right-aligned labels, and the time
 * axis is labelled along the bottom. Points are plotted against their real timestamps, so a sparse
 * window reads as sparse rather than being stretched evenly across the width. The line is drawn in
 * [lineColor] over a fade of the same colour, ending in a dot at the latest value.
 */
@Composable
internal fun NumericHistoryChart(
  series: List<Pair<Long, Float>>,
  unit: String?,
  hours: Int,
  modifier: Modifier = Modifier,
  lineColor: Color = MaterialTheme.colorScheme.primary,
  height: Dp = 180.dp,
) {
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val dotRing = MaterialTheme.colorScheme.surface
  val density = LocalDensity.current

  val values = remember(series) { series.map { it.second } }
  val axis = remember(values) { niceAxis(values.min(), values.max()) }
  val tMin = series.first().first
  val tMax = series.last().first
  val tSpan = (tMax - tMin).takeIf { it > 0L } ?: 1L

  val labelPaint =
    remember(labelColor, density) {
      Paint().apply {
        isAntiAlias = true
        color = labelColor.toArgb()
        textSize = with(density) { 11.sp.toPx() }
      }
    }

  Canvas(modifier = modifier.fillMaxWidth().height(height).padding(top = 4.dp, bottom = 4.dp)) {
    val labelGap = 4.dp.toPx()
    // Widest y label decides how much room the axis gutter needs.
    val yLabelWidth = axis.ticks.maxOf { labelPaint.measureText(axisLabel(it)) }
    val left = yLabelWidth + labelGap
    // Room for the end dot, so it isn't clipped at the right edge.
    val right = size.width - 4.dp.toPx()
    // Top gutter clears the unit caption and the top tick's label; bottom gutter the time row.
    val top = labelPaint.textSize * 2 + labelGap
    val bottom = size.height - labelPaint.textSize - labelGap
    val plotH = (bottom - top).coerceAtLeast(1f)
    val plotW = (right - left).coerceAtLeast(1f)
    val axisSpan = (axis.max - axis.min).takeIf { it > 0f } ?: 1f

    fun yOf(v: Float) = bottom - ((v - axis.min) / axisSpan) * plotH
    fun xOf(t: Long) = left + ((t - tMin).toFloat() / tSpan) * plotW

    // Horizontal value gridlines + right-aligned labels in the left gutter.
    labelPaint.textAlign = Paint.Align.RIGHT
    axis.ticks.forEach { tick ->
      val y = yOf(tick)
      drawLine(
        color = gridColor,
        start = Offset(left, y),
        end = Offset(right, y),
        strokeWidth = 1.dp.toPx(),
      )
      drawContext.canvas.nativeCanvas.drawText(
        axisLabel(tick),
        left - labelGap,
        y + labelPaint.textSize / 3f,
        labelPaint,
      )
    }

    // Time axis: a handful of evenly spaced labels, the first and last kept inside the canvas.
    val timeTicks = 4
    for (i in 0..timeTicks) {
      val t = tMin + tSpan * i / timeTicks
      val x = xOf(t).coerceIn(left, right)
      labelPaint.textAlign =
        when (i) {
          0 -> Paint.Align.LEFT
          timeTicks -> Paint.Align.RIGHT
          else -> Paint.Align.CENTER
        }
      drawContext.canvas.nativeCanvas.drawText(timeLabel(t, hours), x, size.height, labelPaint)
    }

    // Unit caption at the top-left, above the value gutter.
    if (unit != null) {
      labelPaint.textAlign = Paint.Align.LEFT
      drawContext.canvas.nativeCanvas.drawText(unit, 0f, labelPaint.textSize, labelPaint)
    }

    val line = Path()
    series.forEachIndexed { i, (t, v) ->
      val x = xOf(t)
      val y = yOf(v)
      if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    val area =
      Path().apply {
        addPath(line)
        lineTo(xOf(tMax), bottom)
        lineTo(xOf(tMin), bottom)
        close()
      }
    drawPath(
      path = area,
      brush =
        Brush.verticalGradient(
          listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)),
          startY = top,
          endY = bottom,
        ),
    )
    drawPath(
      path = line,
      color = lineColor,
      style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    val end = Offset(xOf(tMax), yOf(series.last().second))
    drawCircle(color = dotRing, radius = 5.dp.toPx(), center = end)
    drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = end)
  }
}

/**
 * On/off-style timeline for non-numeric entities: each state run is a coloured segment sized by its
 * share of the window, the last running on to [endMs]. "Active" states (`on`, `open`, `home`,
 * `playing`, …) read in [activeColor]; `unavailable` and `unknown` in a faint outline tone;
 * everything else in a muted surface tone. With [hours] set, the window's start, middle and end
 * times are labelled underneath.
 */
@Composable
internal fun StateTimeline(
  points: List<HistoryPoint>,
  modifier: Modifier = Modifier,
  endMs: Long = points.last().ts.toEpochMilliseconds(),
  activeColor: Color = MaterialTheme.colorScheme.primary,
  hours: Int? = null,
  barHeight: Dp = 20.dp,
) {
  val idleColor = timelineColor("off", activeColor)
  val missingColor = timelineColor("unavailable", activeColor)
  val tsStart = points.first().ts.toEpochMilliseconds()
  val tsEnd = maxOf(endMs, points.last().ts.toEpochMilliseconds())
  val span = (tsEnd - tsStart).takeIf { it > 0 } ?: 1L
  Column(modifier.fillMaxWidth()) {
    Canvas(
      modifier =
        Modifier.fillMaxWidth()
          .padding(vertical = 4.dp)
          .height(barHeight)
          .clip(RoundedCornerShape(barHeight / 4))
    ) {
      points.forEachIndexed { i, point ->
        val segStart = point.ts.toEpochMilliseconds()
        val segEnd = points.getOrNull(i + 1)?.ts?.toEpochMilliseconds() ?: tsEnd
        val x0 = ((segStart - tsStart).toFloat() / span) * size.width
        val x1 = ((segEnd - tsStart).toFloat() / span) * size.width
        drawRect(
          color =
            when {
              point.state.isActiveState() -> activeColor
              point.state.isMissingState() -> missingColor
              else -> idleColor
            },
          topLeft = Offset(x0, 0f),
          size = Size((x1 - x0).coerceAtLeast(1f), size.height),
        )
      }
    }
    if (hours != null) {
      Row(Modifier.fillMaxWidth()) {
        listOf(tsStart, tsStart + span / 2, tsEnd).forEachIndexed { i, t ->
          Box(Modifier.weight(1f)) {
            Text(
              timeLabel(t, hours),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier =
                Modifier.align(
                  when (i) {
                    0 -> Alignment.CenterStart
                    1 -> Alignment.Center
                    else -> Alignment.CenterEnd
                  }
                ),
            )
          }
        }
      }
    }
  }
}

/**
 * The colour [StateTimeline] draws [state] in: [activeColor] for an active state, a faint outline
 * tone for `unavailable` / `unknown`, a muted one for anything else.
 */
@Composable
internal fun timelineColor(state: String, activeColor: Color): Color =
  when {
    state.isActiveState() -> activeColor
    state.isMissingState() -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
  }

private val ACTIVE_STATES = setOf("on", "open", "home", "playing", "active", "unlocked", "detected")

internal fun String.isActiveState(): Boolean = lowercase() in ACTIVE_STATES

internal fun String.isMissingState(): Boolean = this == "unavailable" || this == "unknown"

/** Value axis: a "nice"-rounded min/max plus the gridline ticks in between. */
private data class ValueAxis(val min: Float, val max: Float, val ticks: List<Float>)

/**
 * Compute a human-friendly value axis (round min/max and ~5 evenly spaced ticks at 1/2/5·10ⁿ
 * intervals) for the data range, the way a charting library would, so the gridlines land on values
 * like 12, 14, 16 rather than the raw data extremes.
 */
private fun niceAxis(dataMin: Float, dataMax: Float, targetTicks: Int = 5): ValueAxis {
  if (!dataMin.isFinite() || !dataMax.isFinite() || dataMax <= dataMin) {
    val v = if (dataMin.isFinite()) dataMin else 0f
    return ValueAxis(v - 1f, v + 1f, listOf(v - 1f, v, v + 1f))
  }
  val step = niceNum((dataMax - dataMin) / (targetTicks - 1), round = true)
  val niceMin = floor(dataMin / step) * step
  val niceMax = ceil(dataMax / step) * step
  val ticks = buildList {
    var t = niceMin
    // Guard against fp drift overshooting the loop bound.
    while (t <= niceMax + step / 2f) {
      add(t)
      t += step
    }
  }
  return ValueAxis(niceMin, niceMax, ticks)
}

/** Round a span to the nearest "nice" number (1, 2, 5 × 10ⁿ). */
private fun niceNum(range: Float, round: Boolean): Float {
  if (range <= 0f) return 1f
  val exponent = floor(log10(range.toDouble()))
  val fraction = range / 10.0.pow(exponent)
  val niceFraction =
    if (round) {
      when {
        fraction < 1.5 -> 1.0
        fraction < 3.0 -> 2.0
        fraction < 7.0 -> 5.0
        else -> 10.0
      }
    } else {
      when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else -> 10.0
      }
    }
  return (niceFraction * 10.0.pow(exponent)).toFloat()
}

private fun axisLabel(v: Float): String =
  if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)

private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** Time-axis label: clock time for a day-or-less window, calendar day for longer ranges. */
internal fun timeLabel(epochMillis: Long, hours: Int): String {
  val zoned = java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
  return (if (hours <= 24) HOUR_FORMAT else DAY_FORMAT).format(zoned)
}
