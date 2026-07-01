@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardSizeConstraints
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.RemoteHaWidgetSurface
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.ha.rc.image.CoilBitmapLoader
import ee.schimke.ha.rc.widgetsProfile
import ee.schimke.terrazzo.LocalHaImageStack
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.LocalThemeStyle
import ee.schimke.terrazzo.widget.LAUNCHER_CELL_HEIGHT_DP
import ee.schimke.terrazzo.widget.LAUNCHER_CELL_WIDTH_DP
import ee.schimke.terrazzo.widget.LauncherGridBounds
import ee.schimke.terrazzo.widget.WidgetSizeClass
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generic "more info" / history screen, opened by long-pressing any dashboard card. Three stacked
 * regions:
 *
 * 1. A live render of the card itself at the top — the same `CachedCardPreview` path the dashboard
 *    and install sheet use, so it looks identical to what the user just pressed.
 * 2. A historical chart per entity the card references. Numeric entities (sensors) draw a line
 *    chart; everything else (lights, switches, binary sensors) draws an on/off-style state
 *    timeline. A range selector re-fetches over 24 h / 3 d / 7 d.
 * 3. "Other relevant links" — add the card to the home screen (the existing widget-install flow)
 *    and, for live instances, open the entity in Home Assistant's own history view.
 *
 * History is fetched on demand via [HaSession.fetchHistory]; the card preview renders against the
 * [snapshot] captured at long-press time, so it matches the dashboard frame even on a session that
 * doesn't poll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardHistoryScreen(
  session: HaSession,
  card: CardConfig,
  snapshot: HaSnapshot,
  onBack: () -> Unit,
  onAddToHomeScreen: () -> Unit,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  // Pre-fetched history to seed the first composition. Production leaves this null and the section
  // fetches asynchronously (showing a spinner meanwhile); deterministic screenshot previews pass a
  // synchronously-computed series so the render shows the settled chart on the first frame rather
  // than racing the async fetch and capturing the spinner (the #409 flaky-render investigation).
  initialHistory: Map<String, List<HistoryPoint>>? = null,
) {
  val entityIds = remember(card) { card.historyEntityIds() }
  var hours by remember { mutableIntStateOf(24) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(text = card.historyTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    }
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .padding(contentPadding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      CardPreviewCard(session = session, card = card, snapshot = snapshot)

      if (entityIds.isEmpty()) {
        Text(
          "This card isn't linked to a Home Assistant entity, so there's " + "no history to show.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        HistorySection(
          session = session,
          entityIds = entityIds,
          snapshot = snapshot,
          hours = hours,
          onHoursChange = { hours = it },
          initialHistory = initialHistory,
        )
      }

      LinksSection(session = session, entityIds = entityIds, onAddToHomeScreen = onAddToHomeScreen)
    }
  }
}

/**
 * Top region: the card previewed as a launcher widget on a resizable slot.
 *
 * The card's [WidgetSizeClass] — the same one [ee.schimke.terrazzo.widget.WidgetInstaller] pins
 * when you tap "Add to Home Screen" — defines a resize range in whole launcher cells, with a
 * smallest and largest slot. We draw that grid behind the card, dash-outline the smallest and
 * largest sizes, and let the user drag the corner handle to resize the widget across the range a
 * cell at a time. The card re-renders in launcher mode (fixed size, full-bleed surface, no in-app
 * chrome) at each slot, so it's a faithful preview of how the widget reflows before committing to a
 * home-screen position.
 */
@Composable
private fun CardPreviewCard(session: HaSession, card: CardConfig, snapshot: HaSnapshot) {
  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
      ResizableLauncherPreview(session = session, card = card, snapshot = snapshot)
    }
  }
}

@Composable
private fun ResizableLauncherPreview(session: HaSession, card: CardConfig, snapshot: HaSnapshot) {
  val context = LocalContext.current
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val style = LocalThemeStyle.current
  val dark = LocalIsDarkTheme.current
  val haTheme = remember(style, dark) { haThemeFor(style, dark) }
  val haImageStack = LocalHaImageStack.current
  val imageLoader =
    haImageStack?.imageLoader
      ?: remember(context) { SingletonImageLoader.get(context.applicationContext) }
  val bitmapLoader =
    remember(context, session.baseUrl, imageLoader) {
      CoilBitmapLoader(
        context.applicationContext,
        imageLoader = imageLoader,
        baseUrl = session.baseUrl,
      )
    }

  // Quantise the card's continuous size hint onto the launcher size
  // class, then read its resize range in whole cells from the same
  // appwidget-provider metadata the launcher uses.
  val sizeClass =
    remember(card, snapshot) {
      WidgetSizeClass.forConstraints(registry.cardSizeConstraints(card, snapshot))
    }
  val bounds = remember(sizeClass, context) { sizeClass.gridBounds(context) }

  // Current slot, in cells. Starts at the comfortable default the
  // widget pins at; resized by dragging the corner handle.
  var cellsW by remember(sizeClass) { mutableIntStateOf(bounds.defaultCellsW) }
  var cellsH by remember(sizeClass) { mutableIntStateOf(bounds.defaultCellsH) }

  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val smallestColor = MaterialTheme.colorScheme.tertiary
  val largestColor = MaterialTheme.colorScheme.secondary
  val currentColor = MaterialTheme.colorScheme.primary

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    LauncherGrid(
      bounds = bounds,
      cellsW = cellsW,
      cellsH = cellsH,
      onResize = { w, h ->
        cellsW = w
        cellsH = h
      },
      gridColor = gridColor,
      smallestColor = smallestColor,
      largestColor = largestColor,
      currentColor = currentColor,
    ) {
      CachedCardPreview(
        // Size-independent: the captured document reflows to the slot
        // at playback via its runtime size-breakpoints, so the same
        // bytes serve every cell size — keying on cellsW/cellsH would
        // only force a redundant (blocking) re-encode on every drag step.
        cacheKey = LauncherPreviewKey(card, style, dark),
        // Capture with the launcher's constrained op vocabulary —
        // the same widgetsProfile TerrazzoWidgetProvider and the
        // install sheet use — so a card that the launcher runtime
        // would reject or render empty looks the same here as on
        // the home screen, not artificially richer.
        profile = widgetsProfile,
        card = card,
        snapshot = snapshot,
        liveBindings = registry.cardDataSignature(card, snapshot) == null,
        bitmapLoader = bitmapLoader,
        modifier = Modifier.fillMaxSize(),
      ) {
        ProvideCardRegistry(registry) {
          ProvideHaTheme(haTheme) {
            // The same fixed-size, full-bleed surface the
            // launcher widget wraps the card in (no in-app
            // card chrome), so the preview matches the slot.
            ProvideCardSizeMode(CardSizeMode.Fixed) {
              ProvideCardChrome(enabled = false) {
                RemoteHaWidgetSurface {
                  RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
                }
              }
            }
          }
        }
      }
    }

    ResizeLegend(
      bounds = bounds,
      cellsW = cellsW,
      cellsH = cellsH,
      smallestColor = smallestColor,
      largestColor = largestColor,
      currentColor = currentColor,
    )
  }
}

/**
 * Draws the launcher grid (one cell = [LAUNCHER_CELL_WIDTH_DP] × [LAUNCHER_CELL_HEIGHT_DP] dp)
 * spanning the largest slot, dash-outlines the smallest and largest sizes, and overlays [content]
 * (the card) sized to the current slot with a draggable resize handle at its bottom-right corner.
 */
@Composable
internal fun LauncherGrid(
  bounds: LauncherGridBounds,
  cellsW: Int,
  cellsH: Int,
  onResize: (Int, Int) -> Unit,
  gridColor: Color,
  smallestColor: Color,
  largestColor: Color,
  currentColor: Color,
  content: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  val cellWpx = with(density) { LAUNCHER_CELL_WIDTH_DP.dp.toPx() }
  val cellHpx = with(density) { LAUNCHER_CELL_HEIGHT_DP.dp.toPx() }
  // Drag distance not yet spent on a cell step, in px. Reset between
  // gestures so a fresh drag starts from the current slot edge.
  var dragX by remember { mutableFloatStateOf(0f) }
  var dragY by remember { mutableFloatStateOf(0f) }

  val gridWidthDp = (bounds.maxCellsW * LAUNCHER_CELL_WIDTH_DP).dp
  val gridHeightDp = (bounds.maxCellsH * LAUNCHER_CELL_HEIGHT_DP).dp
  // Snap the slot (card, outline and handle) to whole cells, the way the
  // launcher does. The embedded player is rebuilt at each slot size (a
  // re-used RemoteComposePlayer paints blank / doesn't fill when
  // re-measured at a new size — see WrapAdaptiveRemoteDocumentPlayer), so
  // we step per cell rather than tweening through intermediate sizes.
  val currentWidthDp = (cellsW * LAUNCHER_CELL_WIDTH_DP).dp
  val currentHeightDp = (cellsH * LAUNCHER_CELL_HEIGHT_DP).dp

  Box(modifier = Modifier.size(gridWidthDp, gridHeightDp)) {
    // Backdrop: cell grid + smallest/largest size outlines.
    Canvas(modifier = Modifier.matchParentSize()) {
      val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
      for (i in 0..bounds.maxCellsW) {
        val x = (i * cellWpx).coerceAtMost(size.width)
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
      }
      for (j in 0..bounds.maxCellsH) {
        val y = (j * cellHpx).coerceAtMost(size.height)
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
      }
      // Largest size = the whole grid; smallest = top-left block.
      drawRect(
        color = largestColor,
        topLeft = Offset.Zero,
        size = Size(size.width, size.height),
        style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
      )
      drawRect(
        color = smallestColor,
        topLeft = Offset.Zero,
        size = Size(bounds.minCellsW * cellWpx, bounds.minCellsH * cellHpx),
        style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
      )
    }

    // The card at its current slot, anchored top-left so it grows
    // toward the handle.
    Box(
      modifier =
        Modifier.align(Alignment.TopStart)
          .size(currentWidthDp, currentHeightDp)
          .border(2.dp, currentColor)
    ) {
      content()
    }

    // Resize handle at the current slot's bottom-right corner. A
    // drag of one cell's width / height steps the slot by one cell,
    // clamped to the size class's min/max range.
    Box(
      modifier =
        Modifier.align(Alignment.TopStart)
          .offset(
            x = currentWidthDp - HANDLE_SIZE_DP.dp / 2,
            y = currentHeightDp - HANDLE_SIZE_DP.dp / 2,
          )
          .size(HANDLE_SIZE_DP.dp)
          .clip(CircleShape)
          .background(currentColor)
          .pointerInput(bounds) {
            detectDragGestures(
              onDragEnd = {
                dragX = 0f
                dragY = 0f
              },
              onDragCancel = {
                dragX = 0f
                dragY = 0f
              },
            ) { change, drag ->
              change.consume()
              dragX += drag.x
              dragY += drag.y
              var w = cellsW
              var h = cellsH
              while (dragX >= cellWpx && w < bounds.maxCellsW) {
                w++
                dragX -= cellWpx
              }
              while (dragX <= -cellWpx && w > bounds.minCellsW) {
                w--
                dragX += cellWpx
              }
              while (dragY >= cellHpx && h < bounds.maxCellsH) {
                h++
                dragY -= cellHpx
              }
              while (dragY <= -cellHpx && h > bounds.minCellsH) {
                h--
                dragY += cellHpx
              }
              // Don't bank drag past the clamp, or the user
              // has to "unwind" it before the slot moves back.
              if (w == bounds.maxCellsW) dragX = dragX.coerceAtMost(0f)
              if (w == bounds.minCellsW) dragX = dragX.coerceAtLeast(0f)
              if (h == bounds.maxCellsH) dragY = dragY.coerceAtMost(0f)
              if (h == bounds.minCellsH) dragY = dragY.coerceAtLeast(0f)
              if (w != cellsW || h != cellsH) onResize(w, h)
            }
          },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.Filled.OpenInFull,
        contentDescription = "Resize widget preview",
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(16.dp),
      )
    }
  }
}

/** Colour-keyed caption: current / smallest / largest slot, in cells. */
@Composable
internal fun ResizeLegend(
  bounds: LauncherGridBounds,
  cellsW: Int,
  cellsH: Int,
  smallestColor: Color,
  largestColor: Color,
  currentColor: Color,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      LegendSwatch(currentColor, "Now $cellsW×$cellsH")
      LegendSwatch(smallestColor, "Min ${bounds.minCellsW}×${bounds.minCellsH}")
      LegendSwatch(largestColor, "Max ${bounds.maxCellsW}×${bounds.maxCellsH}")
    }
    Text(
      "Drag the handle to resize the widget",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
    Text(label, style = MaterialTheme.typography.labelSmall)
  }
}

private const val HANDLE_SIZE_DP = 28

private data class LauncherPreviewKey(
  val card: CardConfig,
  val style: ThemeStyle,
  val dark: Boolean,
)

/** Middle region: range chips + one chart block per entity. */
@Composable
private fun HistorySection(
  session: HaSession,
  entityIds: List<String>,
  snapshot: HaSnapshot,
  hours: Int,
  onHoursChange: (Int) -> Unit,
  initialHistory: Map<String, List<HistoryPoint>>? = null,
) {
  Text("History", style = MaterialTheme.typography.titleMedium)

  androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    RANGE_OPTIONS.forEach { (label, optionHours) ->
      FilterChip(
        selected = hours == optionHours,
        onClick = { onHoursChange(optionHours) },
        label = { Text(label) },
      )
    }
  }

  // produceState keyed by (entityIds, hours) re-runs the fetch whenever
  // the user picks a new range; null means "still loading". Seeded with
  // [initialHistory] so a preview renders the settled chart on the first
  // frame rather than racing the async fetch and capturing the spinner.
  val history by
    produceState<Map<String, List<HistoryPoint>>?>(
      initialValue = initialHistory,
      session,
      entityIds,
      hours,
    ) {
      value = null
      value = runCatching { session.fetchHistory(entityIds, hours) }.getOrDefault(emptyMap())
    }

  val current = history
  if (current == null) {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  entityIds.forEachIndexed { index, id ->
    if (index > 0) HorizontalDivider()
    EntityHistoryBlock(
      entityId = id,
      name = friendlyName(snapshot, id),
      unit = unitOf(snapshot, id),
      points = current[id].orEmpty(),
      hours = hours,
    )
  }
}

@Composable
private fun EntityHistoryBlock(
  entityId: String,
  name: String,
  unit: String?,
  points: List<HistoryPoint>,
  hours: Int,
) {
  // Numeric samples paired with their wall-clock timestamp, so the chart can
  // place each point on a real time axis (gaps included) rather than spacing
  // them evenly by index.
  val series =
    remember(points) {
      points.mapNotNull { p -> p.state.toFloatOrNull()?.let { p.ts.toEpochMilliseconds() to it } }
    }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(name, style = MaterialTheme.typography.titleSmall)
    when {
      points.isEmpty() ->
        Text(
          "No history recorded for this entity.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      series.size >= 2 -> {
        Text(
          summariseNumeric(series.map { it.second }, unit),
          style = MaterialTheme.typography.bodySmall,
        )
        NumericHistoryChart(series = series, unit = unit, hours = hours)
      }
      else -> {
        Text("${points.size} state changes", style = MaterialTheme.typography.bodySmall)
        StateTimeline(points = points)
      }
    }
  }
}

/**
 * Line chart with axes, mirroring Home Assistant's own more-info history graph: the unit sits at
 * the top-left, "nice"-rounded value gridlines run across with right-aligned labels, and the time
 * axis is labelled along the bottom. Points are plotted against their real timestamps, so a sparse
 * window reads as sparse rather than being stretched evenly across the width.
 */
@Composable
private fun NumericHistoryChart(series: List<Pair<Long, Float>>, unit: String?, hours: Int) {
  val lineColor = MaterialTheme.colorScheme.primary
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
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

  Canvas(modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 4.dp, bottom = 4.dp)) {
    val labelGap = 4.dp.toPx()
    // Widest y label decides how much room the axis gutter needs.
    val yLabelWidth = axis.ticks.maxOf { labelPaint.measureText(axisLabel(it)) }
    val left = yLabelWidth + labelGap
    val right = size.width
    // Top gutter clears the unit caption; bottom gutter clears the time row.
    val top = labelPaint.textSize + labelGap
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

    // Time axis: a handful of evenly spaced labels, centre-aligned, with a
    // faint vertical gridline at each.
    labelPaint.textAlign = Paint.Align.CENTER
    val timeTicks = 4
    for (i in 0..timeTicks) {
      val t = tMin + tSpan * i / timeTicks
      val x = xOf(t).coerceIn(left, right)
      drawLine(
        color = gridColor,
        start = Offset(x, top),
        end = Offset(x, bottom),
        strokeWidth = 1.dp.toPx(),
      )
      // Keep the first/last labels inside the canvas rather than clipped.
      val tx =
        when (i) {
          0 -> (x + labelPaint.measureText(timeLabel(t, hours)) / 2f)
          timeTicks -> (x - labelPaint.measureText(timeLabel(t, hours)) / 2f)
          else -> x
        }
      drawContext.canvas.nativeCanvas.drawText(timeLabel(t, hours), tx, size.height, labelPaint)
    }

    // Unit caption at the top-left, above the value gutter.
    if (unit != null) {
      labelPaint.textAlign = Paint.Align.LEFT
      drawContext.canvas.nativeCanvas.drawText(unit, 0f, labelPaint.textSize, labelPaint)
    }

    val path = Path()
    series.forEachIndexed { i, (t, v) ->
      val x = xOf(t)
      val y = yOf(v)
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
  }
}

/**
 * On/off-style timeline for non-numeric entities: each state run is a coloured segment sized by its
 * share of the window. "Active" states (`on`, `open`, `home`, `playing`, …) read in the primary
 * colour; everything else in a muted surface tone.
 */
@Composable
private fun StateTimeline(points: List<HistoryPoint>) {
  val activeColor = MaterialTheme.colorScheme.primary
  val idleColor = MaterialTheme.colorScheme.surfaceVariant
  val tsStart = points.first().ts.toEpochMilliseconds()
  val tsEnd = points.last().ts.toEpochMilliseconds()
  val span = (tsEnd - tsStart).takeIf { it > 0 } ?: 1L
  Canvas(modifier = Modifier.fillMaxWidth().height(28.dp).padding(vertical = 4.dp)) {
    points.forEachIndexed { i, point ->
      val segStart = point.ts.toEpochMilliseconds()
      val segEnd = points.getOrNull(i + 1)?.ts?.toEpochMilliseconds() ?: tsEnd
      val x0 = ((segStart - tsStart).toFloat() / span) * size.width
      val x1 = ((segEnd - tsStart).toFloat() / span) * size.width
      drawRect(
        color = if (point.state.isActiveState()) activeColor else idleColor,
        topLeft = Offset(x0, 0f),
        size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), size.height),
      )
    }
  }
}

/** Bottom region: install + open-in-HA links. */
@Composable
private fun LinksSection(
  session: HaSession,
  entityIds: List<String>,
  onAddToHomeScreen: () -> Unit,
) {
  val context = LocalContext.current
  val isDemo = remember(session) { DemoData.isDemo(session.baseUrl) }

  Text("Other actions", style = MaterialTheme.typography.titleMedium)

  FilledTonalButton(onClick = onAddToHomeScreen, modifier = Modifier.fillMaxWidth()) {
    Icon(Icons.AutoMirrored.Filled.AddToHomeScreen, contentDescription = null)
    Text("  Add to Home Screen")
  }

  // The deep link only makes sense against a real instance; demo's
  // `demo://` base url has no web history view to open.
  if (!isDemo && entityIds.isNotEmpty()) {
    OutlinedButton(
      onClick = {
        val url = "${session.baseUrl.trimEnd('/')}/history?entity_id=${entityIds.first()}"
        runCatching {
          context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(Icons.Filled.Link, contentDescription = null)
      Text("  Open in Home Assistant")
    }
  }
}

private val RANGE_OPTIONS: List<Pair<String, Int>> = listOf("24 h" to 24, "3 d" to 72, "7 d" to 168)

private fun friendlyName(snapshot: HaSnapshot, entityId: String): String =
  snapshot.states[entityId]?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: entityId

private fun unitOf(snapshot: HaSnapshot, entityId: String): String? =
  snapshot.states[entityId]?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content

private fun summariseNumeric(values: List<Float>, unit: String?): String {
  val min = values.min()
  val max = values.max()
  val current = values.last()
  val suffix = unit?.let { " $it" } ?: ""
  val fmt: (Float) -> String = { v ->
    if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)
  }
  return "now ${fmt(current)}$suffix · range ${fmt(min)}–${fmt(max)}$suffix · ${values.size} samples"
}

private val ACTIVE_STATES = setOf("on", "open", "home", "playing", "active", "unlocked", "detected")

private fun String.isActiveState(): Boolean = lowercase() in ACTIVE_STATES

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
private fun timeLabel(epochMillis: Long, hours: Int): String {
  val zoned = java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
  return (if (hours <= 24) HOUR_FORMAT else DAY_FORMAT).format(zoned)
}
