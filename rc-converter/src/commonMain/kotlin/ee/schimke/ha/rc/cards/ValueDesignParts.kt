@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaIconIdentity
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The parts the value cards' designs arrange (card-designs/ui-builder/designs/`gauge.uid` and
 * `statistic.uid`): each draws one form of its card for the card being drawn ([LocalCardInput]);
 * which form a surface gets is the design's `card-size-mode`, `size-tiers` and `centered-cell`. The
 * card design renderer draws them through [components]; ha-cards-catalog's `HaGaugeCard*` and
 * `HaStatisticCard*` wrappers put the same calls on UI Builder's shelf.
 */
object ValueDesignParts {
  private val gauge = GaugeCardConverter()
  private val statistic = StatisticCardConverter()

  /** The card being drawn, or a [type] card for `sensor.temperature` in UI Builder's canvas. */
  @Composable
  private fun input(type: String): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          type,
          JsonObject(
            mapOf(
              "type" to JsonPrimitive(type),
              "entity" to JsonPrimitive("sensor.temperature"),
            )
          ),
        ),
        HaSnapshot(),
      )

  /** `ha-cards/gauge-card-full`: the gauge's half-circle dial over its name, on a dashboard. */
  @Composable
  fun GaugeFull(modifier: RemoteModifier) {
    val (card, snapshot) = input("gauge")
    gauge.Full(card, snapshot, modifier)
  }

  /**
   * `ha-cards/gauge-card-wide`: the arc on the left, value and name beside it, for a short cell.
   */
  @Composable
  fun GaugeWide(modifier: RemoteModifier) {
    val (card, snapshot) = input("gauge")
    gauge.Wide(card, snapshot, modifier)
  }

  /** `ha-cards/gauge-card-stacked`: the half-arc as a backdrop with the value over it. */
  @Composable
  fun GaugeStacked(modifier: RemoteModifier) {
    val (card, snapshot) = input("gauge")
    gauge.Stacked(card, snapshot, modifier)
  }

  /**
   * `ha-cards/statistic-card-full`: the statistic, its name and period; [compact] for a Wear Small
   * container, [tight] for a one-row launcher widget.
   */
  @Composable
  fun StatisticFull(compact: Boolean, tight: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input("statistic")
    statistic.FullStatistic(card, snapshot, modifier, compact = compact, tight = tight)
  }

  /**
   * `ha-cards/statistic-card-icon-chip`: the tinted icon and value for a narrow cell, the name kept
   * under a generic icon (card-information-hierarchy.md).
   */
  @Composable
  fun StatisticIconChip(modifier: RemoteModifier) {
    val (card, snapshot) = input("statistic")
    RemoteHaIconChip(
      statistic.iconChipData(card, snapshot),
      modifier,
      showName =
        HaIconIdentity.chipShowsName(
          card,
          card.raw["entity"]?.jsonPrimitive?.content?.let { snapshot.states[it] },
        ),
    )
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/gauge-card-full" to { _, modifier -> GaugeFull(modifier) },
      "ha-cards/gauge-card-wide" to { _, modifier -> GaugeWide(modifier) },
      "ha-cards/gauge-card-stacked" to { _, modifier -> GaugeStacked(modifier) },
      "ha-cards/statistic-card-full" to
        { node, modifier ->
          StatisticFull(node.bool("compact"), node.bool("tight"), modifier)
        },
      "ha-cards/statistic-card-icon-chip" to { _, modifier -> StatisticIconChip(modifier) },
    )
}
