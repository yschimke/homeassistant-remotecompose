@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.HaHeroScale
import ee.schimke.ha.rc.components.HaPresentation
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.LocalCompactEntityLayout
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.RemoteHaSensorHero
import ee.schimke.ha.rc.components.SensorHeroChromeDp
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import ee.schimke.ha.rc.framedDesignTemplate
import ee.schimke.ha.rc.haReading
import kotlinx.serialization.json.jsonPrimitive

/**
 * The parts a sensor card's design arranges (card-designs/ui-builder/designs/sensor.uid): each
 * draws one form of the card for the card being drawn ([LocalCardInput]). Which form shows at which
 * size, on which surface, is the design's: these only draw, and [Cell] only says which of its slots
 * the card and its canvas call for. The card design renderer draws them through [components];
 * ha-cards-catalog's `HaSensorCard*` wrappers put the same calls on UI Builder's shelf.
 */
object SensorDesignParts {
  private val converter = SensorCardConverter()

  /** The card being drawn, or a placeholder sensor with a history line in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: requireNotNull(
        converter.framedDesignTemplate(
          DesignProperties.build {
            string(EntityDesign.DOMAIN, "sensor")
            enum(SeriesDesign.HAS_DATA, 1)
          }
        )
      )

  /** `ha-cards/sensor-card-full`: the sensor card, its value over its history line. */
  @Composable
  fun Full(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    converter.FullSensor(card, snapshot, modifier)
  }

  /**
   * `ha-cards/sensor-card-identity`: a narrow cell's identity, the reading's own visual (a
   * thermometer, a battery) over its value, or the icon chip; it fills its cell itself.
   */
  @Composable
  fun Identity() {
    val (card, snapshot) = input()
    converter.IdentityTier(card, snapshot)
  }

  /**
   * `ha-cards/sensor-card-history-hero`: a roomy launcher cell with a line, the value promoted and
   * the line given the rest of the cell's height.
   */
  @Composable
  fun HistoryHero(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    RemoteHaSensorHero(
      converter.sensorData(card, snapshot),
      captureHeroScale() ?: WearHeroScale,
      plotHeightDp = (LocalWidgetCaptureSizeDp.current?.heightDp ?: 0) - SensorHeroChromeDp,
      modifier = modifier,
    )
  }

  /**
   * `ha-cards/sensor-card-reading`: the reading in a [form]: `expanded` (it grows with the cell),
   * `standard`, `compact`, or `cell` (standard in a launcher cell three columns wide, compact in a
   * narrower one). [inset] pads it horizontally by `m` or `l`, or `none`. [live] `false` reads it
   * as a Wear container does, without the snapshot's history.
   */
  @Composable
  fun Reading(form: String, inset: String, live: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input()
    val id = card.raw["entity"]?.jsonPrimitive?.content
    val entity = id?.let { snapshot.states[it] }
    val name = converter.nameFor(card, snapshot)
    val reading =
      if (live) haReading(id, entity, name, snapshot = snapshot) else haReading(id, entity, name)
    val padded =
      when (inset) {
        "m" -> modifier.padding(horizontal = HaSpace.M.rdp)
        "l" -> modifier.padding(horizontal = HaSpace.L.rdp)
        else -> modifier
      }
    RemoteHaMeasurement(reading, presentationOf(form), padded)
  }

  @Composable
  private fun presentationOf(name: String): HaPresentation =
    when (name) {
      "expanded" -> HaPresentation.Expanded
      "compact" -> HaPresentation.Compact
      "cell" ->
        // Standard's card-sized value needs a three-column widget's width.
        if ((LocalWidgetCaptureSizeDp.current?.widthDp ?: 0) >= 200) HaPresentation.Standard
        else HaPresentation.Compact
      else -> HaPresentation.Standard
    }

  /**
   * `ha-cards/sensor-card-cell`: which slot the card and its canvas call for. On a dashboard,
   * [wrap]. In a Wear container (a compact entity layout), [wear]. In a launcher cell with a hero
   * scale, [historyHero] when the sensor has a line to draw, else [readingHero]; in a launcher cell
   * wide enough for a hero but too short, [readingRow] when it has no line. [other] otherwise.
   */
  @Composable
  fun Cell(
    wrap: @Composable () -> Unit,
    wear: @Composable () -> Unit,
    historyHero: @Composable () -> Unit,
    readingHero: @Composable () -> Unit,
    readingRow: @Composable () -> Unit,
    other: @Composable () -> Unit,
  ) {
    if (LocalCardSizeMode.current == CardSizeMode.Wrap) return wrap()
    val (card, snapshot) = input()
    val history = converter.hasHistory(card, snapshot)
    when {
      LocalCompactEntityLayout.current -> wear()
      captureHeroScale() != null && history -> historyHero()
      captureHeroScale() != null -> readingHero()
      (LocalWidgetCaptureSizeDp.current?.widthDp ?: 0) >= HaHeroScale.MinWidthDp && !history ->
        readingRow()
      else -> other()
    }
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/sensor-card-full" to { _, modifier -> Full(modifier) },
      "ha-cards/sensor-card-identity" to { _, _ -> Identity() },
      "ha-cards/sensor-card-history-hero" to { _, modifier -> HistoryHero(modifier) },
      "ha-cards/sensor-card-reading" to
        { node, modifier ->
          Reading(
            node.string("form", "standard"),
            node.string("inset", "none"),
            node.bool("live", true),
            modifier,
          )
        },
      "ha-cards/sensor-card-cell" to
        { node, modifier ->
          Cell(
            wrap = { node.Slot("wrap", modifier) },
            wear = { node.Slot("wear", modifier) },
            historyHero = { node.Slot("historyHero", modifier) },
            readingHero = { node.Slot("readingHero", modifier) },
            readingRow = { node.Slot("readingRow", modifier) },
            other = { node.Slot("other", modifier) },
          )
        },
    )
}
