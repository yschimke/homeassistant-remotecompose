package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.RemoteMinSizeGate
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.EntityRowMinHeightDp
import ee.schimke.ha.rc.components.HaEntitiesData
import ee.schimke.ha.rc.components.HaEntitiesFit
import ee.schimke.ha.rc.components.HaEntityRowData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaEntities
import ee.schimke.ha.rc.components.RemoteHaEntitiesFitted
import ee.schimke.ha.rc.components.entityLooks
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.icons.withIcons
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA `entities` card — a titled list of entity rows. Each entry is either a bare `entity_id` or `{
 * entity, name?, icon?, tap_action? }`.
 *
 * HA's special rows — an entry with a `type` and no `entity` (`section`, `divider`, `weblink`,
 * `buttons`, `call-service` / `perform-action`, `cast`, `conditional`, `custom:*`) — are not drawn:
 * they are not entity rows, and drawing them as one gave a "—" row with a switch.
 */
class EntitiesCardConverter : CardConverter {
  override val cardType: String = CardTypes.ENTITIES

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val rows = entityRows(card).size
    val title = if (card.raw["title"] != null) 36 else 0
    return title + 16 + EntityRowMinHeightDp * rows // title + top-pad + one touch-target row each.
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
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> FullList(card, snapshot, modifier, maxRows = Int.MAX_VALUE)
      CardSizeMode.Fixed ->
        when (LocalFixedCanvasAxis.current) {
          BreakpointAxis.Height -> WearList(card, snapshot, modifier)
          BreakpointAxis.Width -> LauncherList(card, snapshot, modifier)
        }
    }
  }

  /**
   * A Wear slot: every container is the same width and the small and large ones differ only in
   * height, so the single gate is on height ([LocalFixedCanvasAxis]). Both tiers are a list of
   * whole rows — icon, name, state — never a strip of glance cells, whose unbounded labels ran into
   * each other across a 184dp slot and whose icon column overflowed its height.
   * * **Small** (`h < 80`, ~44dp of content): the first [WEAR_SMALL_ROWS] rows, no title.
   * * **Large** (`h ≥ 80`, ~96dp of content): the title over the first [WEAR_LARGE_ROWS] rows (one
   *   more without a title), at body size.
   *
   * Rows share the slot's height and centre in their share, so a short list fills the slot. The
   * rows' live on/off looks are created once, outside the gate, and shared by both tiers, so a
   * baked design template shows each entity's real state and no `<entity>.is_on` is registered
   * twice.
   */
  @Composable
  private fun WearList(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content
    val largeRows = if (title != null) WEAR_LARGE_ROWS else WEAR_LARGE_ROWS + 1
    val rows = rows(card, snapshot).take(maxOf(WEAR_SMALL_ROWS, largeRows))
    // Both tiers draw the same entities, so their live on/off looks are registered once, here,
    // and shared: a design template bakes them, and neither branch registers `is_on` twice.
    val looks = entityLooks(rows)
    RemoteSizeBreakpoint(
      thresholdsDp = intArrayOf(WEAR_LARGE_MIN_HEIGHT_DP),
      axis = BreakpointAxis.Height,
      modifier = modifier.fillMaxSize(),
    ) { tier ->
      when (tier) {
        0 ->
          RemoteHaEntitiesFitted(
            HaEntitiesData(title = null, rows = rows.take(WEAR_SMALL_ROWS)),
            HaEntitiesFit.Compact,
            RemoteModifier.fillMaxSize(),
            looks = looks,
          )
        else ->
          RemoteHaEntitiesFitted(
            HaEntitiesData(title = title, rows = rows.take(largeRows)),
            HaEntitiesFit.Compact,
            RemoteModifier.fillMaxSize(),
            largeText = true,
            looks = looks,
          )
      }
    }
  }

  /**
   * A launcher widget. One 2-D gate ([RemoteMinSizeGate], a single expression, so it survives
   * alpha010's #224 collapse of nested and `.and()` gates):
   * * **List** (narrower than [LAUNCHER_EXPANDED_MIN_WIDTH_DP], or too short for every row at
   *   [HaEntitiesFit.Expanded] height): the Wrap list with dense rows — four 15sp rows plus the
   *   title fill a `3×2` cell only with 4dp rather than 6dp row padding.
   * * **Expanded** (wide enough, and tall enough for the title plus every row at
   *   [HaEntitiesFit.Expanded]'s 36dp): the same rows spread over the widget's height with a bigger
   *   icon and the value a step up, so a `4×3` or `5×4` widget reads larger rather than showing a
   *   strip of glance cells in a mostly empty box.
   *
   * The height minimum comes from the row count, so a long list stays on the dense rows until the
   * widget can hold every row expanded. Width not height used to be the only axis because the
   * matrix and launcher pin width; the launcher capture knows both dimensions (it selects the tier
   * at capture time), and the matrix's fixed cells pin both.
   */
  @Composable
  private fun LauncherList(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val title = card.raw["title"]?.jsonPrimitive?.content
    val captureSize = LocalWidgetCaptureSizeDp.current
    val sizedAtCapture = captureSize != null
    val rowCount = entityRows(card).size
    val fit = HaEntitiesFit.Expanded
    val minHeightDp =
      fit.verticalPaddingDp +
        (if (title != null) fit.titleHeightDp else 0) +
        rowCount * fit.rowMinHeightDp
    RemoteMinSizeGate(
      minWidthDp = LAUNCHER_EXPANDED_MIN_WIDTH_DP,
      minHeightDp = minHeightDp,
      modifier = modifier.fillMaxSize(),
    ) { roomy ->
      if (roomy) {
        val rows = rows(card, snapshot)
        RemoteHaEntitiesFitted(
          HaEntitiesData(title = title, rows = rows),
          fit,
          RemoteModifier.fillMaxSize(),
          // A launcher capture (live widget or design template) records only this tier, so its
          // rows can register `is_on` for the bake to fill. Recording both tiers (the matrix), the
          // dense list already registers them: these rows take the captured entities' state.
          looks = if (sizedAtCapture) entityLooks(rows) else null,
        )
      } else {
        FullList(
          card,
          snapshot,
          RemoteModifier.fillMaxWidth(),
          // A short launcher slot shows whole rows, then reveals more on resize. Keep the
          // dashboard/unsized adaptive document unchanged.
          maxRows =
            captureSize
              ?.takeIf { it.heightDp < 120 }
              ?.let { ((it.heightDp - 20) / 28).coerceAtLeast(1) } ?: Int.MAX_VALUE,
          forceTitle = captureSize == null || captureSize.heightDp >= 120,
          dense = true,
        )
      }
    }
  }

  @Composable
  private fun FullList(
    card: CardConfig,
    snapshot: HaSnapshot,
    modifier: RemoteModifier,
    maxRows: Int,
    forceTitle: Boolean = true,
    dense: Boolean = false,
  ) {
    val title = if (forceTitle) card.raw["title"]?.jsonPrimitive?.content else null
    val rows = rows(card, snapshot).take(maxRows)
    RemoteHaEntities(HaEntitiesData(title = title, rows = rows), modifier = modifier, dense = dense)
  }

  /** Every configured entity as a row. */
  @Composable
  private fun rows(card: CardConfig, snapshot: HaSnapshot): List<HaEntityRowData> =
    entityRows(card).map { (eid, row, toggle) ->
      val entity = eid?.let { snapshot.states[it] }
      val name =
        row?.get("name")?.jsonPrimitive?.content
          ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
          ?: eid
          ?: "—"
      val tapCfg = row?.get("tap_action")?.jsonObject
      val tapAction = if (tapCfg != null) parseHaAction(tapCfg, eid) else defaultTapActionFor(eid)
      val icons = HaIconMap.entityIcons(row?.get("icon")?.jsonPrimitive?.content, entity)
      HaEntityRowData(
        entityId = eid,
        name = name,
        state = LiveValues.state(eid, formatState(entity, snapshot)),
        icon = icons.icon,
        accent = HaStateColor.toggleAccentFor(eid, entity).withIcons(icons),
        tapAction = tapAction,
        toggle = toggle,
      )
    }

  /** A configured row drawn as an entity row; [toggle] is whether it ends in a switch. */
  internal data class EntityRow(val entityId: String?, val row: JsonObject?, val toggle: Boolean)

  internal companion object {
    /**
     * The card's `entities:` drawn as entity rows, in order. A special row (a `type` and no
     * `entity`) is dropped. A row switches only for an entity outside [READ_ONLY_DOMAINS] and
     * without a non-toggle row `type` ([NO_TOGGLE_ROW_TYPES]).
     */
    fun entityRows(card: CardConfig): List<EntityRow> {
      val entries: List<JsonElement> = card.raw["entities"] as? JsonArray ?: emptyList()
      return entries.mapNotNull { el ->
        val (eid, row) = normalize(el)
        val type = (row?.get("type") as? JsonPrimitive)?.contentOrNull
        if (eid == null && type != null) return@mapNotNull null
        val toggle =
          eid != null &&
            eid.substringBefore('.') !in READ_ONLY_DOMAINS &&
            type !in NO_TOGGLE_ROW_TYPES
        EntityRow(eid, row, toggle)
      }
    }

    private fun normalize(el: JsonElement): Pair<String?, JsonObject?> =
      when (el) {
        is JsonPrimitive -> el.content to null
        is JsonObject -> (el["entity"]?.jsonPrimitive?.content) to el
        else -> null to null
      }

    /**
     * Row types that name an entity but show a value, never a switch: `attribute` shows one of its
     * attributes, `simple-entity` its state text.
     */
    private val NO_TOGGLE_ROW_TYPES = setOf("attribute", "simple-entity")

    private val TEMPLATE_CONFIG_KEYS = setOf("type", "title", "entities")

    /**
     * Domains with an on/off state that nothing can switch: HA's entities card gives them its
     * simple row (the state text, "Detected" / "Clear") rather than the toggle row.
     */
    private val READ_ONLY_DOMAINS =
      setOf("binary_sensor", "sensor", "device_tracker", "person", "update")

    /**
     * Wear height gate: between the small slot (~44dp of content, 60dp in the matrix) and the large
     * one (~96dp, 112dp in the matrix).
     */
    private const val WEAR_LARGE_MIN_HEIGHT_DP = 80

    /** Compact rows (20dp: an 18dp icon beside a 13sp line) in the small slot's ~44dp. */
    private const val WEAR_SMALL_ROWS = 2

    /** Body-size rows (~23dp) under a ~22dp title in the large slot's ~96dp. */
    private const val WEAR_LARGE_ROWS = 3

    /**
     * Narrowest launcher widget that takes the expanded rows: a `4×N` cell (~288dp) and wider; a
     * `3×N` cell keeps the dense list.
     */
    private const val LAUNCHER_EXPANDED_MIN_WIDTH_DP = 260
  }
}
