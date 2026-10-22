@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
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
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardEntityIds
import ee.schimke.ha.rc.components.HaHeroScale
import ee.schimke.ha.rc.components.HaMeasureVisual
import ee.schimke.ha.rc.components.HaPresentation
import ee.schimke.ha.rc.components.HaSensorCardData
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.LocalCompactEntityLayout
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.RemoteHaSensorCard
import ee.schimke.ha.rc.components.RemoteHaSensorHero
import ee.schimke.ha.rc.components.SensorHeroChromeDp
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

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> FullSensor(card, snapshot, modifier)
      CardSizeMode.Fixed ->
        if (LocalCompactEntityLayout.current) {
          // Wear has current values but no history, and its 60 dp slot cannot fit the full
          // sensor/history panel. Keep the reading's live text and numeric bindings, with the
          // theme's typed instrument, in both Wear containers.
          val id = card.raw["entity"]?.jsonPrimitive?.content
          val reading = haReading(id, id?.let { snapshot.states[it] }, nameFor(card, snapshot))
          RemoteBox(modifier.fillMaxSize()) {
            CenteredCell {
              RemoteHaMeasurement(reading, HaPresentation.Compact, RemoteModifier.fillMaxWidth())
            }
          }
        } else if (captureHeroScale() != null && hasHistory(card, snapshot)) {
          // A roomy launcher widget with a line: the value promoted, the line given the height.
          val size = LocalWidgetCaptureSizeDp.current!!
          RemoteHaSensorHero(
            sensorData(card, snapshot),
            captureHeroScale()!!,
            plotHeightDp = size.heightDp - SensorHeroChromeDp,
            modifier = modifier.fillMaxSize(),
          )
        } else if (captureHeroScale() != null) {
          // A roomy launcher widget with no line to draw: the reading grows with the widget.
          val id = card.raw["entity"]?.jsonPrimitive?.content
          val reading =
            haReading(
              id,
              id?.let { snapshot.states[it] },
              nameFor(card, snapshot),
              snapshot = snapshot,
            )
          RemoteHaMeasurement(reading, HaPresentation.Expanded, modifier.fillMaxSize())
        } else if (
          (LocalWidgetCaptureSizeDp.current?.widthDp ?: 0) >= HaHeroScale.MinWidthDp &&
            !hasHistory(card, snapshot)
        ) {
          // A wide, short launcher widget with no line to draw: the reading beside its name, not
          // an empty history panel.
          val id = card.raw["entity"]?.jsonPrimitive?.content
          val reading =
            haReading(
              id,
              id?.let { snapshot.states[it] },
              nameFor(card, snapshot),
              snapshot = snapshot,
            )
          RemoteBox(modifier.fillMaxSize()) {
            CenteredCell {
              RemoteHaMeasurement(
                reading,
                // Standard's card-sized value needs a three-column widget's width.
                if ((LocalWidgetCaptureSizeDp.current?.widthDp ?: 0) >= 200) HaPresentation.Standard
                else HaPresentation.Compact,
                RemoteModifier.fillMaxWidth().padding(horizontal = HaSpace.L.rdp),
              )
            }
          }
        } else
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
    RemoteHaSensorCard(sensorData(card, snapshot), modifier = modifier)
  }

  private fun sensorData(card: CardConfig, snapshot: HaSnapshot): HaSensorCardData {
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

  private fun hasHistory(card: CardConfig, snapshot: HaSnapshot): Boolean {
    val id = card.raw["entity"]?.jsonPrimitive?.content ?: return false
    return snapshot.history[id].orEmpty().count { it.state.toFloatOrNull() != null } >= 2
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
