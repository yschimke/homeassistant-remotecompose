@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
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
import ee.schimke.ha.rc.components.HaMeasureVisual
import ee.schimke.ha.rc.components.HaPresentation
import ee.schimke.ha.rc.components.HaSensorCardData
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.RemoteHaSensorCard
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.haReading
import ee.schimke.ha.rc.icons.HaIconMap
import kotlinx.serialization.json.jsonPrimitive

/**
 * `sensor` card — entity name + big value + a small inline sparkline of recent values. Pulls
 * history from `HaSnapshot.history[entityId]` and reuses the history-graph row visual as a hero
 * tile.
 *
 * In [CardSizeMode.Fixed] the converter runs the shared icon-+-state ladder
 * (adaptive-card-layouts.md §"Icon + state row/tile"): a narrow cell drops to the identity tier
 * (tinted icon + value, or the typed measurement visual for a temperature or battery — see
 * [IdentityTier]; sparkline + name dropped — the sparkline is the card's P5, so it's the first
 * thing to go), while the full [RemoteHaSensorCard] promotes the sparkline back on cells with the
 * width to host it, and is [CenteredCell]-wrapped so a tall cell fills rather than top-glues.
 */
class SensorCardConverter : CardConverter {
  override val cardType: String = CardTypes.SENSOR

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 120

  /**
   * A sensor card renders from a card design: the value is the state binding, the history line is
   * [SeriesDesign]'s sample and range bindings, and the range label is `card.range`. Structure: the
   * entity's [EntityDesign] properties (the line's accent) and whether there is a line at all.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.single(card, snapshot, TEMPLATE_CONFIG_KEYS) {
      val id = card.raw["entity"]?.jsonPrimitive?.content
      enum(SeriesDesign.HAS_DATA, if (id != null && SeriesDesign.hasData(snapshot, id)) 1 else 0)
    }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val input = EntityDesign.singleTemplate(cardType, properties) ?: return null
    val slot = input.snapshot.states.keys.single()
    val history = SeriesDesign.placeholderHistory(properties[SeriesDesign.HAS_DATA] == "1")
    return input.copy(snapshot = input.snapshot.copy(history = mapOf(slot to history)))
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val ids = listOfNotNull(card.raw["entity"]?.jsonPrimitive?.content)
    return CardSnapshotBindings(
      mapOf("card.range" to rangeLabel(card)),
      emptyMap(),
      emptyMap(),
      SeriesDesign.slotBindings(ids, snapshot),
    )
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> FullSensor(card, snapshot, modifier)
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(thresholdsDp = intArrayOf(120), modifier = modifier.fillMaxSize()) {
          tier ->
          when (tier) {
            0 -> IdentityTier(card, snapshot)
            else -> CenteredCell { FullSensor(card, snapshot, RemoteModifier.fillMaxWidth()) }
          }
        }
    }
  }

  /**
   * The narrow-cell identity tier. A sensor whose kind has its own visual (a temperature's
   * thermometer, a battery's battery) shows that visual over its value ([HaPresentation.Chip]): the
   * pictogram says what kind of reading it is, where a generic tinted icon only says "sensor".
   * Every other sensor keeps the shared [RemoteHaIconChip].
   */
  @Composable
  private fun IdentityTier(card: CardConfig, snapshot: HaSnapshot) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val reading =
      haReading(
        entityId,
        entityId?.let { snapshot.states[it] },
        nameFor(card, snapshot),
        snapshot = snapshot,
      )
    when (reading.kind.visual) {
      HaMeasureVisual.Thermometer,
      HaMeasureVisual.Battery ->
        RemoteHaMeasurement(reading, HaPresentation.Chip, RemoteModifier.fillMaxSize())
      HaMeasureVisual.Arc,
      HaMeasureVisual.Bar ->
        RemoteHaIconChip(iconChipData(card, snapshot), RemoteModifier.fillMaxSize())
    }
  }

  @Composable
  private fun FullSensor(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val history = entityId?.let { snapshot.history[it].orEmpty() } ?: emptyList()
    val numeric = history.mapNotNull { it.state.toFloatOrNull() }

    RemoteHaSensorCard(
      HaSensorCardData(
        entityId = entityId,
        name = nameFor(card, snapshot),
        valueLabel = formatState(entity, snapshot),
        accent = HaStateColor.activeFor(entity),
        points = numeric,
        rangeLabel = rangeLabel(card),
      ),
      modifier = modifier,
    )
  }

  /**
   * Identity-tier payload shaped as [HaTileData] so the shared [RemoteHaIconChip] can render the
   * icon + live value.
   */
  @Composable
  private fun iconChipData(card: CardConfig, snapshot: HaSnapshot): HaTileData {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val accent = HaStateColor.activeFor(entity).rc
    return HaTileData(
      entityId = entityId,
      name = nameFor(card, snapshot),
      state = LiveValues.state(entityId, formatState(entity, snapshot)),
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
      ?: "Sensor"
  }
}

private val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name", "hours_to_show")

private fun rangeLabel(card: CardConfig): String =
  "Last ${card.raw["hours_to_show"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24}h"
