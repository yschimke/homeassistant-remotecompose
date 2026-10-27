@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.HISTORY_PLOT_HEIGHT_DP
import ee.schimke.ha.rc.components.HaHistoryGraphData
import ee.schimke.ha.rc.components.HaType
import ee.schimke.ha.rc.components.RemoteHaHistoryGraph
import ee.schimke.ha.rc.components.RemoteHaHistoryGraphIdentity
import ee.schimke.ha.rc.components.RemoteHaMarkdown
import ee.schimke.ha.rc.components.RemoteHaMarkdownIdentity
import ee.schimke.ha.rc.components.RemoteHaStatisticsGraph
import ee.schimke.ha.rc.components.SPARKLINE_HEIGHT_DP
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts the graph and text cards' designs arrange (card-designs/ui-builder/designs/
 * `history-graph.uid`, `statistics-graph.uid` and `markdown.uid`): each draws one form of its card
 * for the card being drawn ([LocalCardInput]); which form a surface gets is the design's
 * `card-size-mode` and `size-tiers` (the bulk cards' single width gate, #224). The card design
 * renderer draws them through [components]; ha-cards-catalog's wrappers put the same calls on UI
 * Builder's shelf.
 */
object GraphDesignParts {
  private val history = HistoryGraphCardConverter()
  private val statistics = StatisticsGraphCardConverter()
  private val markdown = MarkdownCardConverter()

  /** The card being drawn, or a [type] card with [fields] in UI Builder's canvas. */
  @Composable
  private fun input(type: String, vararg fields: Pair<String, String>): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          type,
          JsonObject(
            mapOf("type" to JsonPrimitive(type)) +
              fields.associate { (k, v) -> k to JsonPrimitive(v) }
          ),
        ),
        HaSnapshot(),
      )

  /**
   * `ha-cards/history-graph-card-full`: every entity's trace; [framed] draws each at the family's
   * framed height (a dashboard), [compactEmptyState] keeps the empty state to one line (a Wear
   * slot).
   */
  @Composable
  fun HistoryFull(framed: Boolean, compactEmptyState: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input("history-graph", "entity" to "sensor.temperature")
    val data = history.historyData(card, snapshot)
    val plot = launcherPlot(data.rows.size, data.title != null)
    if (plot != null)
      RemoteHaHistoryGraph(
        data.copy(rows = data.rows.take(plot.rows)),
        modifier,
        singleEmptyState = true,
        compactEmptyState = true,
        plotHeightDp = plot.heightDp,
      )
    else if (framed)
      RemoteHaHistoryGraph(
        data,
        modifier = modifier,
        singleEmptyState = true,
        plotHeightDp = HISTORY_PLOT_HEIGHT_DP,
      )
    else
      RemoteHaHistoryGraph(
        data,
        modifier,
        singleEmptyState = true,
        compactEmptyState = compactEmptyState,
      )
  }

  /** `ha-cards/history-graph-card-identity`: the first trace's spark and latest value. */
  @Composable
  fun HistoryIdentity(modifier: RemoteModifier) {
    val (card, snapshot) = input("history-graph", "entity" to "sensor.temperature")
    LauncherIdentity(history.historyData(card, snapshot), modifier)
  }

  /**
   * The trend identity on a launcher widget: the summary at a size the width holds (none in one
   * column) and the line given the rest of the height. Off a launcher, the identity as designed.
   */
  @Composable
  private fun LauncherIdentity(data: HaHistoryGraphData, modifier: RemoteModifier) {
    val cell = LocalWidgetCaptureSizeDp.current
    if (cell == null) {
      RemoteHaHistoryGraphIdentity(data, modifier)
      return
    }
    val valueSize =
      when {
        cell.widthDp < IDENTITY_VALUE_MIN_WIDTH_DP -> null
        cell.widthDp < BULK_IDENTITY_THRESHOLD_DP -> HaType.Label
        else -> HaType.Value
      }
    val chrome = GRAPH_PADDING_DP + RANGE_LINE_DP + (if (valueSize != null) 30 else 0) + 8
    RemoteHaHistoryGraphIdentity(
      data,
      modifier,
      valueSize = valueSize,
      plotHeightDp = (cell.heightDp - chrome).coerceIn(SPARKLINE_HEIGHT_DP, MAX_PLOT_HEIGHT_DP),
    )
  }

  /** `ha-cards/statistics-graph-card-full`: the statistics chart. */
  @Composable
  fun StatisticsFull(modifier: RemoteModifier) {
    val (card, snapshot) = input("statistics-graph", "entity" to "sensor.energy")
    val data = statistics.statisticsGraphData(card, snapshot)
    // A narrow launcher cell gets the trend identity, as history-graph's design does.
    val cell = LocalWidgetCaptureSizeDp.current
    if (cell != null && cell.widthDp < BULK_IDENTITY_THRESHOLD_DP) {
      LauncherIdentity(
        HaHistoryGraphData(title = data.title, rangeLabel = data.rangeLabel, rows = data.rows),
        modifier,
      )
      return
    }
    // A stacked chart is one plot with a legend line per series under it.
    val plot =
      if (data.stacked)
        launcherPlot(1, data.title != null, extraDp = data.rows.size * STACKED_LEGEND_LINE_DP)
      else launcherPlot(data.rows.size, data.title != null)
    if (plot == null) RemoteHaStatisticsGraph(data, modifier = modifier)
    else
      RemoteHaStatisticsGraph(
        if (data.stacked) data else data.copy(rows = data.rows.take(plot.rows)),
        modifier = modifier,
        plotHeightDp = plot.heightDp,
      )
  }

  /** `ha-cards/markdown-card-full`: the title over every block. */
  @Composable
  fun MarkdownFull(modifier: RemoteModifier) {
    val (card, snapshot) = input("markdown", "content" to "**Hello**")
    // A roomy launcher widget with twice the note's height to spare sets it larger, rather than a
    // few small lines in the middle of a big cell.
    val cell = LocalWidgetCaptureSizeDp.current
    val large =
      cell != null &&
        captureHeroScale() != null &&
        markdown.naturalHeightDp(card, snapshot) * 2 <= cell.heightDp
    RemoteHaMarkdown(markdown.markdownData(card, snapshot), modifier = modifier, large = large)
  }

  /** `ha-cards/markdown-card-identity`: the title and first line, for a narrow cell. */
  @Composable
  fun MarkdownIdentity(modifier: RemoteModifier) {
    // Text wraps, so a two-column launcher widget holds the whole note, not just its first line.
    val cell = LocalWidgetCaptureSizeDp.current
    if (
      cell != null &&
        cell.widthDp >= MARKDOWN_FULL_MIN_WIDTH_DP &&
        cell.heightDp >= MARKDOWN_FULL_MIN_HEIGHT_DP
    ) {
      MarkdownFull(modifier)
      return
    }
    val (card, snapshot) = input("markdown", "content" to "**Hello**")
    RemoteHaMarkdownIdentity(
      markdown.markdownData(card, snapshot),
      modifier,
      narrow = cell != null && cell.widthDp < MARKDOWN_FULL_MIN_WIDTH_DP,
    )
  }

  /** How many graph rows a launcher widget shows, and how tall each one's plot is. */
  private class LauncherPlot(val rows: Int, val heightDp: Int)

  /**
   * A launcher widget's share of its height for each of [rowCount] plots, after the card's padding,
   * title ([hasTitle]), range line and [extraDp] more (a stacked chart's legend): as many rows as
   * fit at the compact height, each then given an equal share of the rest, so a taller widget draws
   * taller lines rather than more blank card (docs/architecture/adaptive-card-layouts.md, "Earn the
   * canvas"). Null off a launcher capture.
   */
  @Composable
  private fun launcherPlot(rowCount: Int, hasTitle: Boolean, extraDp: Int = 0): LauncherPlot? {
    val cell = LocalWidgetCaptureSizeDp.current ?: return null
    if (rowCount == 0) return null
    val chrome =
      GRAPH_PADDING_DP +
        RANGE_LINE_DP +
        extraDp +
        (if (hasTitle) TITLE_LINE_DP + GRAPH_GAP_DP else 0)
    val perRow = GRAPH_GAP_DP + ROW_LABEL_DP + GRAPH_GAP_DP
    val room = cell.heightDp - chrome
    val rows = (room / (perRow + SPARKLINE_HEIGHT_DP)).coerceIn(1, rowCount)
    val heightDp = (room / rows - perRow).coerceIn(SPARKLINE_HEIGHT_DP, MAX_PLOT_HEIGHT_DP)
    return LauncherPlot(rows, heightDp)
  }

  // What a graph card spends besides its plots (RemoteHaHistoryGraph): 10 dp padding above and
  // below, a Title line, a Caption range line and a Label line per row, 6 dp between each, with a
  // little slack for font metrics.
  private const val GRAPH_PADDING_DP = 24
  private const val TITLE_LINE_DP = 22
  private const val RANGE_LINE_DP = 16
  private const val ROW_LABEL_DP = 18
  private const val GRAPH_GAP_DP = 6
  private const val STACKED_LEGEND_LINE_DP = 22
  private const val MAX_PLOT_HEIGHT_DP = 220

  /** From two launcher columns and two rows a markdown note shows whole. */
  private const val MARKDOWN_FULL_MIN_WIDTH_DP = 100
  private const val MARKDOWN_FULL_MIN_HEIGHT_DP = 150

  /** Below this width (one launcher column) the identity's summary is left to the line. */
  private const val IDENTITY_VALUE_MIN_WIDTH_DP = 100

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/history-graph-card-full" to
        { node, modifier ->
          HistoryFull(node.bool("framed"), node.bool("compactEmptyState"), modifier)
        },
      "ha-cards/history-graph-card-identity" to { _, modifier -> HistoryIdentity(modifier) },
      "ha-cards/statistics-graph-card-full" to { _, modifier -> StatisticsFull(modifier) },
      "ha-cards/markdown-card-full" to { _, modifier -> MarkdownFull(modifier) },
      "ha-cards/markdown-card-identity" to { _, modifier -> MarkdownIdentity(modifier) },
    )
}
