@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaStatisticCardData
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaStatisticCard
import ee.schimke.ha.rc.icons.HaIconMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `statistic` card — single statistic value (mean / min / max / sum) for one entity. Reads
 * `HaSnapshot.statistics[entityId]` and aggregates the configured `stat_type` over the snapshot's
 * window. Falls back to the entity's current state when there's no statistic data — keeps the card
 * useful even before recorder backfill.
 *
 * In [CardSizeMode.Fixed] the converter runs the shared icon-+-state ladder
 * (adaptive-card-layouts.md §"Icon + state row/tile"): a narrow cell drops to the
 * [RemoteHaIconChip] identity tier (tinted icon + value, name + period dropped), while the full
 * [RemoteHaStatisticCard] keeps the trend + period and is [CenteredCell]-wrapped so a tall cell
 * fills rather than top-glues.
 */
class StatisticCardConverter : CardConverter {
  override val cardType: String = CardTypes.STATISTIC

  // Baked, non-bindable content (see CardConverter.dataSignature):
  // re-encode when any referenced entity's snapshot data moves.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 110

  /**
   * A statistic card renders from a card design: the aggregate is `<slot>.statistic`, the unit
   * `<slot>.unit` and the period line `card.period`. Structure: the entity's [EntityDesign]
   * properties (accent) and which of the unit and period lines exist.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.single(card, snapshot, TEMPLATE_CONFIG_KEYS) {
      enum(HAS_UNIT, if (unitOf(card, snapshot) != null) 1 else 0)
      enum(HAS_PERIOD, if (periodLabel(card) != null) 1 else 0)
    }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val input =
      EntityDesign.singleTemplate(cardType, properties) {
        if (properties[HAS_PERIOD] == "1") put("period", "period")
      } ?: return null
    if (properties[HAS_UNIT] != "1") return input
    val entity = input.snapshot.states.values.single()
    val withUnit =
      entity.copy(
        attributes =
          JsonObject(entity.attributes + ("unit_of_measurement" to JsonPrimitive("unit")))
      )
    return input.copy(snapshot = HaSnapshot(states = mapOf(entity.entityId to withUnit)))
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val id =
      card.raw["entity"]?.jsonPrimitive?.content
        ?: return CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(id), 0)
    val strings = buildMap {
      put("$slot.statistic", valueLabel(card, snapshot))
      unitOf(card, snapshot)?.let { put("$slot.unit", it) }
      periodLabel(card)?.let { put("card.period", it) }
    }
    return CardSnapshotBindings(strings, emptyMap(), emptyMap(), emptyMap())
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> FullStatistic(card, snapshot, modifier)
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(thresholdsDp = intArrayOf(96), modifier = modifier.fillMaxSize()) {
          tier ->
          when (tier) {
            0 -> RemoteHaIconChip(iconChipData(card, snapshot), RemoteModifier.fillMaxSize())
            else -> CenteredCell { FullStatistic(card, snapshot, RemoteModifier.fillMaxWidth()) }
          }
        }
    }
  }

  @Composable
  private fun FullStatistic(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val unit = unitOf(card, snapshot)
    val period = periodLabel(card)

    RemoteHaStatisticCard(
      HaStatisticCardData(
        entityId = entityId,
        name = nameFor(card, snapshot),
        // Its own binding: the aggregate isn't the entity's state.
        valueLabel = LiveValues.named(entityId, "statistic", valueLabel(card, snapshot)),
        unit = unit,
        periodLabel = period,
        accent = HaStateColor.activeFor(entity),
      ),
      modifier = modifier,
    )
  }

  /**
   * Identity-tier payload shaped as [HaTileData] so the shared [RemoteHaIconChip] can render the
   * icon + statistic value.
   */
  @Composable
  private fun iconChipData(card: CardConfig, snapshot: HaSnapshot): HaTileData {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val accent = HaStateColor.activeFor(entity).rc
    return HaTileData(
      entityId = entityId,
      name = nameFor(card, snapshot),
      // The aggregate's own binding, as in the full tier: it isn't the entity's state.
      state = LiveValues.named(entityId, "statistic", valueLabel(card, snapshot)),
      icon = HaIconMap.resolve(card.raw["icon"]?.jsonPrimitive?.content, entity),
      accent = HaToggleAccent(activeAccent = accent, inactiveAccent = accent, toggleable = false),
    )
  }

  private fun nameFor(card: CardConfig, snapshot: HaSnapshot): String {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    return card.raw["name"]?.jsonPrimitive?.content
      ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
      ?: entityId
      ?: "Statistic"
  }

  /**
   * Aggregate the configured `stat_type` over the snapshot window, falling back to the entity's
   * current state.
   */
  private fun valueLabel(card: CardConfig, snapshot: HaSnapshot): String {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val statType = card.raw["stat_type"]?.jsonPrimitive?.content ?: "mean"
    val statistics = entityId?.let { snapshot.statistics[it].orEmpty() } ?: emptyList()
    val numericValue =
      if (statistics.isNotEmpty()) {
        val values = statistics.mapNotNull {
          when (statType) {
            "min" -> it.min
            "max" -> it.max
            "sum" -> it.sum
            "state" -> it.state
            else -> it.mean
          }
        }
        when (statType) {
          "min" -> values.minOrNull()
          "max" -> values.maxOrNull()
          "sum" -> values.sum().takeIf { values.isNotEmpty() }
          else -> values.average().takeIf { values.isNotEmpty() }
        }
      } else null
    return numericValue?.let { formatValue(it) }
      ?: entity?.state?.takeUnless { it == "unknown" || it == "unavailable" }
      ?: "—"
  }
}

private fun formatValue(d: Double): String = decimalText(d, 2)

private val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name", "stat_type", "period")
private const val HAS_UNIT = "has_unit"
private const val HAS_PERIOD = "has_period"

private fun unitOf(card: CardConfig, snapshot: HaSnapshot): String? {
  val entityId = card.raw["entity"]?.jsonPrimitive?.content ?: return null
  return snapshot.states[entityId]?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content
}

private fun periodLabel(card: CardConfig): String? {
  val statType = card.raw["stat_type"]?.jsonPrimitive?.content ?: "mean"
  return card.raw["period"]?.jsonPrimitive?.content?.let { p ->
    "${p.replaceFirstChar { it.uppercaseChar() }} • ${statType.replaceFirstChar { it.uppercaseChar() }}"
  }
}
