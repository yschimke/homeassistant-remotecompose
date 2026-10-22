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
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.HaGlanceCellData
import ee.schimke.ha.rc.components.HaGlanceData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaGlance
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.icons.withIcons
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
    if (CompactLauncherList(card, snapshot, modifier)) return
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
      val icons = HaIconMap.entityIcons(row?.get("icon")?.jsonPrimitive?.content, entity)
      HaGlanceCellData(
        entityId = eid,
        name = name,
        state = LiveValues.state(eid, formatState(entity, snapshot)),
        icon = icons.icon,
        accent = HaStateColor.toggleAccentFor(eid, entity).withIcons(icons),
        tapAction = tapAction,
      )
    }
    // In Fixed mode (launcher widget / Wear slot) the host pins a
    // canvas taller than one row of cells; fill it (grid grows / row
    // centres) instead of gluing the cells to the top over a blank
    // half-cell. In Wrap mode (dashboard) the card flows to its
    // content height, matching the HA reference.
    val fillHeight = LocalCardSizeMode.current == CardSizeMode.Fixed
    // A launcher widget knows its size: as many cells to a row as are wide enough for a name, and
    // as many rows as its height holds, the rest left to a bigger widget.
    val size = LocalWidgetCaptureSizeDp.current
    // Density before identity: a title that leaves no room for a row of cells goes first.
    val shownTitle = title?.takeIf { size == null || fitsTitle(size.heightDp) }
    val grid = size?.let { gridFor(it.widthDp, it.heightDp, shownTitle) }
    RemoteHaGlance(
      HaGlanceData(
        title = shownTitle,
        cells = grid?.let { cells.take(it.first * it.second) } ?: cells,
      ),
      modifier = modifier,
      fillHeight = fillHeight,
      cellsPerRow = grid?.first ?: GLANCE_MAX_CELLS_PER_ROW,
    )
  }

  private companion object {
    val TEMPLATE_CONFIG_KEYS = setOf("type", "title", "entities")

    /** The most cells a glance row holds, as on the dashboard. */
    const val GLANCE_MAX_CELLS_PER_ROW = 3

    /** The narrowest a cell can be and still show a short name: `Kitchen` at label size. */
    const val GLANCE_MIN_CELL_WIDTH_DP = 60

    /** A cell's height: name, icon, state and their spacing. */
    const val GLANCE_CELL_HEIGHT_DP = 76

    /** The card's padding either side, and a title's line under it. */
    const val GLANCE_PADDING_DP = 24
    const val GLANCE_TITLE_DP = 28

    /** Whether a [heightDp] widget holds a title over one row of cells. */
    fun fitsTitle(heightDp: Int): Boolean =
      heightDp >= GLANCE_PADDING_DP + GLANCE_TITLE_DP + GLANCE_CELL_HEIGHT_DP

    /**
     * Cells per row, and rows, that a [widthDp] × [heightDp] widget holds: at least one of each.
     */
    fun gridFor(widthDp: Int, heightDp: Int, title: String?): Pair<Int, Int> {
      val perRow =
        ((widthDp - GLANCE_PADDING_DP) / GLANCE_MIN_CELL_WIDTH_DP).coerceIn(
          1,
          GLANCE_MAX_CELLS_PER_ROW,
        )
      val titleDp = if (title != null) GLANCE_TITLE_DP else 0
      val rows = ((heightDp - GLANCE_PADDING_DP - titleDp) / GLANCE_CELL_HEIGHT_DP).coerceAtLeast(1)
      return perRow to rows
    }
  }

  private fun normalize(el: JsonElement): Pair<String?, JsonObject?> =
    when (el) {
      is JsonPrimitive -> el.content to null
      is JsonObject -> (el["entity"]?.jsonPrimitive?.content) to el
      else -> null to null
    }
}
