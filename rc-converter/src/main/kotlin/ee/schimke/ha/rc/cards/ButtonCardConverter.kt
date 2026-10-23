@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaIconIdentity
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalGridSquareCells
import ee.schimke.ha.rc.LocalInCardContainer
import ee.schimke.ha.rc.RemoteMinSizeGate
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaButton
import ee.schimke.ha.rc.components.RemoteHaButtonIcon
import ee.schimke.ha.rc.components.RemoteHaButtonRow
import ee.schimke.ha.rc.components.RemoteHaIconHero
import ee.schimke.ha.rc.components.RemoteHaToggleButton
import ee.schimke.ha.rc.components.RemoteHaWideHero
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.icons.withIcons
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `button` card. Honours HA's `show_name`, `show_icon` and `show_state` (defaults true, true,
 * false). Toggleable entities (lights / switches / locks / covers / media-players etc) render
 * through [RemoteHaToggleButton] so tap crossfades the accent when the host writes the new state
 * back; read-only entities go through the simpler [RemoteHaButton].
 *
 * In [CardSizeMode.Fixed] the converter encodes a 2-tier ladder driven by the runtime canvas
 * (mirrors the button ladder in `docs/architecture/adaptive-card-layouts.md` §button):
 *
 * * **icon + name** — the full button, self-centred via `fillMaxSize` so it fills / centres rather
 *   than pinning top-left on big cells.
 * * **icon chip** — [RemoteHaButtonIcon], the tinted-chip identity tier for cells too small for the
 *   full button (`1×1` launcher): the name stays under the chip at label size unless the icon is
 *   evocative ([HaIconIdentity]), as card-information-hierarchy.md has it.
 *
 * A Wear slot gates on height instead ([WEAR_SMALL_MAX_HEIGHT_DP]): the Small container takes
 * [RemoteHaButtonRow] (the icon beside the name, and the state with `show_state`), the Large one
 * the full button.
 *
 * The gate is a **single** boolean off a single float expression `min(w, h ·
 * minW/minH).isGreaterThanOrEqualTo(minW)`, which is true only when the cell is both wide enough
 * (`w ≥ minW`) and tall enough (`h ≥ minH`). Encoding the 2-D test as one `min` expression (rather
 * than `w.isGreaterThanOrEqualTo(..) .and(h.isGreaterThanOrEqualTo(..))`) sidesteps the alpha010
 * quirk where `RemoteBoolean .and()` and nested width/height state-layouts collapse to `false` at
 * playback (#224, see `GaugeCardConverter`). It lets the narrow-tall `1×1` cell and a wide-short
 * cell *both* fall to the icon-only tier while the wide-and-tall `2×1` / `3×2` cells keep the name
 * — something no single width-only or height-only gate can do.
 */
class ButtonCardConverter : CardConverter {
  override val cardType: String = CardTypes.BUTTON

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 84

  override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Compact

  /** The icon chip plus its padding, with a few characters of name: narrower and it's no button. */
  override fun gridMinCellWidthDp(card: CardConfig, snapshot: HaSnapshot): Int = MinGridCellWidthDp

  /** HA's square grid cell: the button centres its icon and name in it. */
  override fun gridSquareCell(card: CardConfig, snapshot: HaSnapshot): Boolean = true

  /**
   * A plain entity button renders from a card design: the [EntityDesign] properties of its entity
   * plus `show_name`, which decides whether the name line exists at all.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.single(card, snapshot, TEMPLATE_CONFIG_KEYS) {
      enum(SHOW_NAME, if (showName(card)) 1 else 0)
    }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    EntityDesign.singleTemplate(cardType, properties) {
      if (properties[SHOW_NAME] == "0") put("show_name", false)
    }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val data = buttonData(card, snapshot)
    when (LocalCardSizeMode.current) {
      // In a square grid cell (as narrow as MinGridCellWidthDp, so as short) the compact body
      // keeps the icon and name inside the square.
      CardSizeMode.Wrap -> RenderButton(data, modifier, compact = LocalGridSquareCells.current)
      CardSizeMode.Fixed -> {
        val entity = data.entityId?.let { snapshot.states[it] }
        // The 1×1 chip drops the name only for an evocative icon (card-information-hierarchy.md).
        val chipName = data.showName && HaIconIdentity.chipShowsName(card, entity)
        AdaptiveButton(data, modifier, chipName)
      }
    }
  }

  @Composable
  private fun AdaptiveButton(data: HaButtonData, modifier: RemoteModifier, chipName: Boolean) {
    // `show_icon: false` leaves no icon for the icon-only tier: the full button fills every cell.
    if (!data.showIcon) {
      RenderButton(data, modifier.fillMaxSize(), compact = true)
      return
    }
    val row = captureRowScale()
    if (row != null && data.showName) {
      // A wide, short launcher widget: the button's icon beside its name and (when shown) state.
      RemoteHaWideHero(
        HaTileData(
          entityId = data.entityId,
          name = data.name,
          state = data.state ?: "".rs,
          icon = data.icon,
          accent = data.accent,
          tapAction = data.tapAction,
        ),
        row.copy(name = row.state),
        modifier.fillMaxSize(),
        showState = data.state != null,
      )
      return
    }
    val hero = captureHeroScale()
    if (hero != null && data.showName) {
      // A roomy launcher widget: the button's icon, name and (when shown) state, grown with it.
      RemoteHaIconHero(
        HaTileData(
          entityId = data.entityId,
          name = data.name,
          state = data.state ?: "".rs,
          icon = data.icon,
          accent = data.accent,
          tapAction = data.tapAction,
        ),
        hero,
        modifier.fillMaxSize(),
        showState = data.state != null,
      )
      return
    }
    // In a stack or grid the container is the multi-entity card: its buttons take the size gate
    // below, which keeps a narrow cell to the icon, rather than naming each one.
    if (LocalFixedCanvasAxis.current == BreakpointAxis.Height && !LocalInCardContainer.current) {
      // A Wear slot, wide at either size: the Small container (~44dp of content) is too short for
      // the name under the icon, so it takes the name beside it; the Large one the full button.
      // The name stays at both sizes: an icon alone leaves two buttons on one watch alike
      // (card-information-hierarchy.md).
      RemoteSizeBreakpoint(
        thresholdsDp = intArrayOf(WEAR_SMALL_MAX_HEIGHT_DP),
        modifier = modifier.fillMaxSize(),
        axis = BreakpointAxis.Height,
      ) { tier ->
        if (tier == 0 && data.showName) RemoteHaButtonRow(data, RemoteModifier.fillMaxSize())
        else if (tier == 0) RemoteHaButtonIcon(data, RemoteModifier.fillMaxSize())
        else RenderButton(data, RemoteModifier.fillMaxSize(), compact = true)
      }
      return
    }
    RemoteMinSizeGate(
      minWidthDp = MinIconNameWidthDp,
      minHeightDp = MinIconNameHeightDp,
      modifier = modifier.fillMaxSize(),
    ) { roomy ->
      if (roomy) {
        RenderButton(data, RemoteModifier.fillMaxSize(), compact = true)
      } else {
        RemoteHaButtonIcon(data, RemoteModifier.fillMaxSize(), showName = chipName)
      }
    }
  }

  @Composable
  private fun RenderButton(data: HaButtonData, modifier: RemoteModifier, compact: Boolean = false) {
    if (data.accent.toggleable) {
      RemoteHaToggleButton(data, modifier, compact)
    } else {
      RemoteHaButton(data, modifier, compact)
    }
  }

  private companion object {
    val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name", "icon", "show_name")

    const val SHOW_NAME = "show_name"

    /** Smallest cell width that fits the icon + name tier. */
    const val MinIconNameWidthDp = 96

    /**
     * Smallest cell height that fits the icon + reserved label line: 6 + 44 (chip) + 4 + ~19 (a
     * 16sp `BodyLarge` line) + 6 ≈ 79dp. It was 72, which already left the old 15sp label a few dp
     * short; a cell too short for the line now takes the icon-only tier rather than clipping it.
     */
    const val MinIconNameHeightDp = 80

    /** Narrowest `grid` cell: the compact body (44 dp chip + 12 dp sides) plus a short name. */
    const val MinGridCellWidthDp = 80
  }
}

/** Build the [HaButtonData] payload from card config + live snapshot. */
@Composable
private fun buttonData(card: CardConfig, snapshot: HaSnapshot): HaButtonData {
  val entityId = card.raw["entity"]?.jsonPrimitive?.content
  val entity = entityId?.let { snapshot.states[it] }
  val name =
    card.raw["name"]?.jsonPrimitive?.content
      ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
      ?: entityId
      ?: "Button"
  val showName = showName(card)
  // HA's button card: `show_icon` defaults true, `show_state` false. The state line is the host's
  // `<id>.state` binding (formatState), so it updates live (live-card-updates.md, path 1).
  val showIcon = card.flag("show_icon") ?: true
  val showState = card.flag("show_state") ?: false
  val tapCfg = card.raw["tap_action"]?.jsonObject
  val tapAction =
    if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)

  val icons = HaIconMap.entityIcons(card.raw["icon"]?.jsonPrimitive?.content, entity)
  return HaButtonData(
    entityId = entityId,
    name = name,
    icon = icons.icon,
    accent = HaStateColor.toggleAccentFor(entityId, entity).withIcons(icons),
    showName = showName,
    tapAction = tapAction,
    showIcon = showIcon,
    state =
      if (showState && entity != null) LiveValues.state(entityId, formatState(entity, snapshot))
      else null,
  )
}

private fun showName(card: CardConfig): Boolean = card.flag("show_name") ?: true

private fun CardConfig.flag(key: String): Boolean? =
  (raw[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
