package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.components.HaGlanceCellData
import ee.schimke.ha.rc.components.HaGlanceData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaGlance
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA `glance` card — titled grid of compact entity cells. Each entry is either a bare `entity_id`
 * or `{ entity, name?, icon?, tap_action? }`.
 */
class GlanceCardConverter : CardConverter {
  override val cardType: String = CardTypes.GLANCE

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val title = if (card.raw["title"] != null) 40 else 0
    return title + 112 // HA glance cell is ~112dp tall (icon + name + state).
  }

  /**
   * A titled list of plain entity rows renders from a card design: per row, the [EntityDesign]
   * properties of its entity, plus the row count and the title's size bucket.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.list(card, snapshot, TEMPLATE_CONFIG_KEYS)

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    EntityDesign.listTemplate(cardType, properties)

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content
    val entries: List<JsonElement> = card.raw["entities"]?.jsonArray ?: emptyList()

    val cells = entries.mapNotNull { el ->
      val (eid, row) = normalize(el)
      val entity = eid?.let { snapshot.states[it] }
      val name =
        row?.get("name")?.jsonPrimitive?.content
          ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
          ?: eid
          ?: "—"
      val tapCfg = row?.get("tap_action")?.jsonObject
      val tapAction = if (tapCfg != null) parseHaAction(tapCfg, eid) else defaultTapActionFor(eid)
      HaGlanceCellData(
        entityId = eid,
        name = name,
        state = LiveValues.state(eid, formatState(entity, snapshot)),
        icon = HaIconMap.resolve(row?.get("icon")?.jsonPrimitive?.content, entity),
        accent = HaStateColor.toggleAccentFor(eid, entity),
        tapAction = tapAction,
      )
    }
    // In Fixed mode (launcher widget / Wear slot) the host pins a
    // canvas taller than one row of cells; fill it (grid grows / row
    // centres) instead of gluing the cells to the top over a blank
    // half-cell. In Wrap mode (dashboard) the card flows to its
    // content height, matching the HA reference.
    val fillHeight = LocalCardSizeMode.current == CardSizeMode.Fixed
    RemoteHaGlance(
      HaGlanceData(title = title, cells = cells),
      modifier = modifier,
      fillHeight = fillHeight,
    )
  }

  private companion object {
    val TEMPLATE_CONFIG_KEYS = setOf("type", "title", "entities")
  }

  private fun normalize(el: JsonElement): Pair<String?, JsonObject?> =
    when (el) {
      is JsonPrimitive -> el.content to null
      is JsonObject -> (el["entity"]?.jsonPrimitive?.content) to el
      else -> null to null
    }
}
