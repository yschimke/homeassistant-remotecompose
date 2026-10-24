@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.HISTORY_PLOT_HEIGHT_DP
import ee.schimke.ha.rc.components.RemoteHaHistoryGraph
import ee.schimke.ha.rc.components.RemoteHaHistoryGraphIdentity
import ee.schimke.ha.rc.components.RemoteHaMarkdown
import ee.schimke.ha.rc.components.RemoteHaMarkdownIdentity
import ee.schimke.ha.rc.components.RemoteHaStatisticsGraph
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
    if (framed)
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
    RemoteHaHistoryGraphIdentity(history.historyData(card, snapshot), modifier)
  }

  /** `ha-cards/statistics-graph-card-full`: the statistics chart. */
  @Composable
  fun StatisticsFull(modifier: RemoteModifier) {
    val (card, snapshot) = input("statistics-graph", "entity" to "sensor.energy")
    RemoteHaStatisticsGraph(statistics.statisticsGraphData(card, snapshot), modifier = modifier)
  }

  /** `ha-cards/markdown-card-full`: the title over every block. */
  @Composable
  fun MarkdownFull(modifier: RemoteModifier) {
    val (card, snapshot) = input("markdown", "content" to "**Hello**")
    RemoteHaMarkdown(markdown.markdownData(card, snapshot), modifier = modifier)
  }

  /** `ha-cards/markdown-card-identity`: the title and first line, for a narrow cell. */
  @Composable
  fun MarkdownIdentity(modifier: RemoteModifier) {
    val (card, snapshot) = input("markdown", "content" to "**Hello**")
    RemoteHaMarkdownIdentity(markdown.markdownData(card, snapshot), modifier)
  }

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
