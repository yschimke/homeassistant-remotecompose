@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaIconIdentity
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaMeasureVisual
import ee.schimke.ha.rc.components.HaPresentation
import ee.schimke.ha.rc.components.HaSensorCardData
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.RemoteHaSensorCard
import ee.schimke.ha.rc.design.CardDesigns
import ee.schimke.ha.rc.design.LocalCardInput
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.haReading
import ee.schimke.ha.rc.icons.HaIconMap
import kotlinx.serialization.json.jsonPrimitive

/**
 * `sensor` card — entity name + big value + a small inline sparkline of recent values. Pulls
 * history from `HaSnapshot.history[entityId]` and reuses the history-graph row visual as a hero
 * tile.
 *
 * Its layout is its card design, `sensor.uid` ([SensorDesignParts] has the forms and what picks
 * between them). In a fixed canvas it runs the shared icon-+-state ladder (adaptive-card-layouts.md
 * §"Icon + state row/tile"): a narrow cell drops to the identity tier (tinted icon + value, or the
 * typed measurement visual for a temperature or battery — see [IdentityTier]; sparkline + name
 * dropped — the sparkline is the card's P5, so it's the first thing to go), while the full
 * [RemoteHaSensorCard] promotes the sparkline back on cells with the width to host it, and is
 * [CenteredCell]-wrapped so a tall cell fills rather than top-glues.
 *
 * The range label is the history graph's [historyRangeLabel] over the same `hours_to_show` (24 by
 * default, as in Home Assistant): "Last 24 hours", "Last 7 days", "Last 2 weeks".
 */
class SensorCardConverter : CardConverter {
  override val cardType: String = CardTypes.SENSOR

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 120

  /**
   * The sparkline's samples and y-range are baked from `snapshot.history`, which no host pushes as
   * a binding, so a captured sensor card re-encodes when its entity's history (or state) moves:
   * path 2 of live-card-updates.md, as the history-graph card. The design path pushes the same
   * samples as `SeriesDesign` slot bindings instead.
   */
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String =
    cardDataSignature(cardEntityIds(card), snapshot)

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
      mapOf("card.range" to historyRangeLabel(card)),
      emptyMap(),
      emptyMap(),
      SeriesDesign.slotBindings(ids, snapshot),
    )
  }

  /**
   * The sensor card drawn from its card design, `card-designs/ui-builder/designs/sensor.uid`
   * ([CardDesigns]): which form shows at which size, on the dashboard, a launcher cell or a Wear
   * container, is the design's; [SensorDesignParts] draw the forms.
   */
  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val design = requireNotNull(CardDesigns.design(cardType)) { "no sensor.uid packaged" }
    CompositionLocalProvider(LocalCardInput provides DesignTemplateInput(card, snapshot)) {
      design.Render(modifier)
    }
  }

  /**
   * The narrow-cell identity tier. A sensor whose kind has its own visual (a temperature's
   * thermometer, a battery's battery) shows that visual over its value ([HaPresentation.Chip]): the
   * pictogram says what kind of reading it is, where a generic tinted icon only says "sensor".
   * Every other sensor keeps the shared [RemoteHaIconChip].
   */
  @Composable
  internal fun IdentityTier(card: CardConfig, snapshot: HaSnapshot) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val reading =
      haReading(
        entityId,
        entityId?.let { snapshot.states[it] },
        nameFor(card, snapshot),
        snapshot = snapshot,
      )
    // A sensor's pictogram is the same in every room, so the name stays unless the card picks its
    // own `icon:` (card-information-hierarchy.md, the icon exception).
    val showName = HaIconIdentity.chipShowsName(card, entityId?.let { snapshot.states[it] })
    when (reading.kind.visual) {
      HaMeasureVisual.Thermometer,
      HaMeasureVisual.Battery ->
        RemoteHaMeasurement(
          reading,
          HaPresentation.Chip,
          RemoteModifier.fillMaxSize(),
          chipShowsName = showName,
        )
      HaMeasureVisual.Arc,
      HaMeasureVisual.Bar ->
        RemoteHaIconChip(
          iconChipData(card, snapshot),
          RemoteModifier.fillMaxSize(),
          showName = showName,
        )
    }
  }

  @Composable
  internal fun FullSensor(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    RemoteHaSensorCard(sensorData(card, snapshot), modifier = modifier)
  }

  internal fun sensorData(card: CardConfig, snapshot: HaSnapshot): HaSensorCardData {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val history = entityId?.let { snapshot.history[it].orEmpty() } ?: emptyList()
    return HaSensorCardData(
      entityId = entityId,
      name = nameFor(card, snapshot),
      valueLabel = formatState(entity, snapshot),
      accent = HaStateColor.activeFor(entity),
      points = history.mapNotNull { it.state.toFloatOrNull() },
      rangeLabel = historyRangeLabel(card),
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

  internal fun hasHistory(card: CardConfig, snapshot: HaSnapshot): Boolean {
    val id = card.raw["entity"]?.jsonPrimitive?.content ?: return false
    return snapshot.history[id].orEmpty().count { it.state.toFloatOrNull() != null } >= 2
  }

  internal fun nameFor(card: CardConfig, snapshot: HaSnapshot): String {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    return card.raw["name"]?.jsonPrimitive?.content
      ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
      ?: entityId
      ?: "Sensor"
  }
}

private val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name", "hours_to_show")
