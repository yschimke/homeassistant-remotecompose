package ee.schimke.terrazzo.dashboard

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
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
 * [lineColor] the way the skin's [style] says: smooth or stepped, over a fade of the same colour or
 * bare, with a glow beneath, ending in a dot at the latest value.
 */
@Composable
internal fun NumericHistoryChart(
  series: List<Pair<Long, Float>>,
  unit: String?,
  hours: Int,
  modifier: Modifier = Modifier,
  lineColor: Color = MaterialTheme.colorScheme.primary,
  height: Dp = 180.dp,
  style: HistoryChartStyle = currentScreenSkin().chart,
) {
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val dotRing = currentScreenSkin().panel
  val typeface = MaterialTheme.typography.labelSmall.fontFamily.toTypeface()
  val density = LocalDensity.current

  val values = remember(series) { series.map { it.second } }
  val axis = remember(values) { niceAxis(values.min(), values.max()) }
  val tMin = series.first().first
  val tMax = series.last().first
  val tSpan = (tMax - tMin).takeIf { it > 0L } ?: 1L

  val labelPaint =
    remember(labelColor, density, typeface) {
      Paint().apply {
        isAntiAlias = true
        this.typeface = typeface
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
    val gridDash =
      if (style.dashedGrid) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
      else null
    axis.ticks.forEach { tick ->
      val y = yOf(tick)
      drawLine(
        color = gridColor,
        start = Offset(left, y),
        end = Offset(right, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = gridDash,
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
    var lastY = 0f
    series.forEachIndexed { i, (t, v) ->
      val x = xOf(t)
      val y = yOf(v)
      when {
        i == 0 -> line.moveTo(x, y)
        // A stepped trace holds each value until the next sample, as a meter reads.
        style.stepped -> {
          line.lineTo(x, lastY)
          line.lineTo(x, y)
        }
        else -> line.lineTo(x, y)
      }
      lastY = y
    }
    val area =
      Path().apply {
        addPath(line)
        lineTo(xOf(tMax), bottom)
        lineTo(xOf(tMin), bottom)
        close()
      }
    if (style.fill)
      drawPath(
        path = area,
        brush =
          Brush.verticalGradient(
            listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)),
            startY = top,
            endY = bottom,
          ),
      )
    val lineWidth = style.lineWidth.toPx()
    val join = if (style.stepped) StrokeJoin.Miter else StrokeJoin.Round
    val cap = if (style.stepped) StrokeCap.Square else StrokeCap.Round
    if (style.glow) {
      drawPath(
        path = line,
        color = lineColor.copy(alpha = 0.22f),
        style = Stroke(width = lineWidth * 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }
    drawPath(
      path = line,
      color = lineColor,
      style = Stroke(width = lineWidth, cap = cap, join = join),
    )
    if (style.endDot) {
      val end = Offset(xOf(tMax), yOf(series.last().second))
      drawCircle(color = dotRing, radius = lineWidth + 3.dp.toPx(), center = end)
      drawCircle(color = lineColor, radius = lineWidth + 1.5.dp.toPx(), center = end)
    }
  }
}

/**
 * On/off-style timeline for non-numeric entities: each state run is a coloured segment sized by its
 * share of the window, the last running on to [endMs]. "Active" states (`on`, `open`, `home`,
 * `playing`, …) read in [activeColor]; `unavailable` and `unknown` in a faint outline tone;
 * everything else in a muted surface tone. The skin's [style] draws it as one bar, as runs with
 * gaps between them, or as a strip of lit cells (a segment LCD, an LED meter). With [hours] set,
 * the window's start, middle and end times are labelled underneath.
 */
@Composable
internal fun StateTimeline(
  points: List<HistoryPoint>,
  modifier: Modifier = Modifier,
  endMs: Long = points.last().ts.toEpochMilliseconds(),
  activeColor: Color = MaterialTheme.colorScheme.primary,
  hours: Int? = null,
  barHeight: Dp = 20.dp,
  style: HistoryChartStyle = currentScreenSkin().chart,
) {
  val idleColor = timelineColor("off", activeColor)
  val missingColor = timelineColor("unavailable", activeColor)
  val tsStart = points.first().ts.toEpochMilliseconds()
  val tsEnd = maxOf(endMs, points.last().ts.toEpochMilliseconds())
  val span = (tsEnd - tsStart).takeIf { it > 0 } ?: 1L
  Column(modifier.fillMaxWidth()) {
    // One continuous bar is rounded at its two ends; runs with gaps and cells round their own.
    val clip =
      if (style.cells || style.segmentGap > 0.dp) Modifier
      else Modifier.clip(RoundedCornerShape(barHeight * style.cornerFraction))
    Canvas(
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(barHeight).then(clip)
    ) {
      fun colorOf(state: String) =
        when {
          state.isActiveState() -> activeColor
          state.isMissingState() -> missingColor
          else -> idleColor
        }
      val radius = CornerRadius(size.height * style.cornerFraction)
      if (style.cells) {
        // Fixed cells, each lit in the state at its middle.
        val gap = 2.dp.toPx()
        val count = ((size.width + gap) / (6.dp.toPx() + gap)).toInt().coerceAtLeast(1)
        val cellW = (size.width - gap * (count - 1)) / count
        var run = 0
        for (c in 0 until count) {
          val t = tsStart + ((c + 0.5f) / count * span).toLong()
          while (run + 1 < points.size && points[run + 1].ts.toEpochMilliseconds() <= t) run++
          drawRoundRect(
            color = colorOf(points[run].state),
            topLeft = Offset(c * (cellW + gap), 0f),
            size = Size(cellW, size.height),
            cornerRadius = CornerRadius(minOf(radius.x, cellW / 2)),
          )
        }
        return@Canvas
      }
      val gap = style.segmentGap.toPx()
      // Join consecutive samples of one state into a single run.
      val runs = buildList {
        points.forEachIndexed { i, point ->
          if (i > 0 && points[i - 1].state == point.state) return@forEachIndexed
          add(point)
        }
      }
      runs.forEachIndexed { i, point ->
        val segStart = point.ts.toEpochMilliseconds()
        val segEnd = runs.getOrNull(i + 1)?.ts?.toEpochMilliseconds() ?: tsEnd
        val x0 = ((segStart - tsStart).toFloat() / span) * size.width + if (i > 0) gap / 2 else 0f
        val x1 =
          ((segEnd - tsStart).toFloat() / span) * size.width -
            if (i < runs.lastIndex) gap / 2 else 0f
        val w = (x1 - x0).coerceAtLeast(1f)
        if (gap > 0f) {
          drawRoundRect(
            color = colorOf(point.state),
            topLeft = Offset(x0, 0f),
            size = Size(w, size.height),
            cornerRadius = CornerRadius(minOf(radius.x, w / 2)),
          )
        } else {
          drawRect(
            color = colorOf(point.state),
            topLeft = Offset(x0, 0f),
            size = Size(w, size.height),
          )
        }
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

/** The platform typeface for a skin's font family, for text drawn on a canvas. */
private fun FontFamily?.toTypeface(): Typeface =
  when (this) {
    FontFamily.Monospace -> Typeface.MONOSPACE
    FontFamily.Serif -> Typeface.SERIF
    else -> Typeface.DEFAULT
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
