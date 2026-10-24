@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.widget.LAUNCHER_CELL_HEIGHT_DP
import ee.schimke.terrazzo.widget.LAUNCHER_CELL_WIDTH_DP
import ee.schimke.terrazzo.widget.LauncherGridBounds
import kotlinx.serialization.json.jsonPrimitive

/**
 * A card's history, opened from its long-press actions. Three stacked regions:
 * 1. The card as the dashboard draws it, so the history below is read against what the user
 *    pressed. Sizing it for the launcher is the install sheet's job, not this screen's.
 * 2. A historical chart per entity the card references. Numeric entities (sensors) draw a line
 *    chart; everything else (lights, switches, binary sensors) draws an on/off-style state
 *    timeline. A range selector re-fetches over 24 h / 3 d / 7 d.
 * 3. For live instances, a link to the entity in Home Assistant's own history view.
 *
 * Everything else a card offers (home screen, watch, style, Card Studio) lives in its actions
 * sheet, which the top bar's [onShowActions] reopens, so the two surfaces never offer the same
 * button.
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
  contentPadding: PaddingValues = PaddingValues(0.dp),
  // Pre-fetched history to seed the first composition. Production leaves this null and the section
  // fetches asynchronously (showing a spinner meanwhile); deterministic screenshot previews pass a
  // synchronously-computed series so the render shows the settled chart on the first frame rather
  // than racing the async fetch and capturing the spinner (the #409 flaky-render investigation).
  initialHistory: Map<String, List<HistoryPoint>>? = null,
  onShowActions: (() -> Unit)? = null,
) {
  val entityIds = remember(card) { card.historyEntityIds() }
  var hours by remember { mutableIntStateOf(24) }

  // Skinned in the card style, as the more-info sheet is: the card above sits on its own dashboard
  // background and the charts below draw in the style's grammar.
  ProvideScreenSkin {
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
          actions = {
            onShowActions?.let { show ->
              IconButton(onClick = show) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Card actions")
              }
            }
          },
        )
      }
    ) { innerPadding ->
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        DashboardCardPreview(session, card, snapshot)

        if (entityIds.isEmpty()) {
          Text(
            "This card isn't linked to a Home Assistant entity, so there's " +
              "no history to show.",
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

        OpenInHomeAssistant(session = session, entityIds = entityIds)
      }
    }
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
  // Presets and accessibility actions resize without restarting the ongoing pointer handler.
  val currentCells by rememberUpdatedState(cellsW to cellsH)
  val currentOnResize by rememberUpdatedState(onResize)

  val gridWidthDp = (bounds.maxCellsW * LAUNCHER_CELL_WIDTH_DP).dp
  val gridHeightDp = (bounds.maxCellsH * LAUNCHER_CELL_HEIGHT_DP).dp
  // Snap the card, outline and handle to whole cells. The native document is
  // recaptured at each slot size, selecting the same adaptive layout as the launcher.
  val currentWidthDp = (cellsW * LAUNCHER_CELL_WIDTH_DP).dp
  val currentHeightDp = (cellsH * LAUNCHER_CELL_HEIGHT_DP).dp
  val scroll = rememberScrollState()
  LaunchedEffect(cellsW) {
    // A smaller preset must not leave its card off-screen after panning a wide preview.
    if (scroll.value > ((cellsW - 1) * cellWpx).toInt()) scroll.scrollTo(0)
  }

  Box(Modifier.horizontalScroll(scroll).testTag("widget-grid-viewport")) {
    Box(
      modifier =
        Modifier.padding(end = HANDLE_TOUCH_SIZE_DP.dp / 2, bottom = HANDLE_TOUCH_SIZE_DP.dp / 2)
          .size(gridWidthDp, gridHeightDp)
    ) {
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
      // clamped to the preview grid's sample range.
      Box(
        modifier =
          Modifier.align(Alignment.TopStart)
            .offset(
              x = currentWidthDp - HANDLE_TOUCH_SIZE_DP.dp / 2,
              y = currentHeightDp - HANDLE_TOUCH_SIZE_DP.dp / 2,
            )
            .size(HANDLE_TOUCH_SIZE_DP.dp)
            .testTag("widget-resize-handle")
            .semantics {
              contentDescription = "Resize widget preview"
              stateDescription = "$cellsW by $cellsH cells"
              customActions = buildList {
                if (cellsW < bounds.maxCellsW)
                  add(
                    CustomAccessibilityAction("Make wider") {
                      onResize(cellsW + 1, cellsH)
                      true
                    }
                  )
                if (cellsW > bounds.minCellsW)
                  add(
                    CustomAccessibilityAction("Make narrower") {
                      onResize(cellsW - 1, cellsH)
                      true
                    }
                  )
                if (cellsH < bounds.maxCellsH)
                  add(
                    CustomAccessibilityAction("Make taller") {
                      onResize(cellsW, cellsH + 1)
                      true
                    }
                  )
                if (cellsH > bounds.minCellsH)
                  add(
                    CustomAccessibilityAction("Make shorter") {
                      onResize(cellsW, cellsH - 1)
                      true
                    }
                  )
              }
            }
            .pointerInput(bounds, cellWpx, cellHpx) {
              var gestureCells = currentCells
              detectDragGestures(
                onDragStart = {
                  gestureCells = currentCells
                  dragX = 0f
                  dragY = 0f
                },
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
                var w = gestureCells.first
                var h = gestureCells.second
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
                if (w != gestureCells.first || h != gestureCells.second) {
                  // Pointer events can arrive before recomposition publishes the previous step.
                  gestureCells = w to h
                  currentOnResize(w, h)
                }
              }
            },
        contentAlignment = Alignment.Center,
      ) {
        Box(
          Modifier.size(HANDLE_SIZE_DP.dp).clip(CircleShape).background(currentColor),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.Filled.OpenInFull,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp),
          )
        }
      }
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
      LegendSwatch(largestColor, "Preview ${bounds.maxCellsW}×${bounds.maxCellsH}")
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
private const val HANDLE_TOUCH_SIZE_DP = 48

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

/** Bottom region: the entity's history in Home Assistant's own view, for live instances. */
@Composable
private fun OpenInHomeAssistant(session: HaSession, entityIds: List<String>) {
  val context = LocalContext.current
  val isDemo = remember(session) { DemoData.isDemo(session.baseUrl) }
  // The deep link only makes sense against a real instance; demo's
  // `demo://` base url has no web history view to open.
  if (isDemo || entityIds.isEmpty()) return
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
