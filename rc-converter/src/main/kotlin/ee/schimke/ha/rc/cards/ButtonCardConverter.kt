@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaButton
import ee.schimke.ha.rc.components.RemoteHaToggleButton
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.design.CardDesigns
import ee.schimke.ha.rc.design.LocalCardInput
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
 * Its layout is its card design, `button.uid` ([ButtonDesignParts] has the forms and what picks
 * between them): in a fixed canvas the full button where the cell fits its icon and name, the icon
 * alone where it doesn't (`ha-cards/min-size-gate`, a single 2-D test so the narrow-tall `1×1` cell
 * and a wide-short cell both fall to the icon), a launcher cell with a hero scale its hero forms,
 * and a Wear slot the icon beside its name below [WEAR_SMALL_MAX_HEIGHT_DP], the full button above.
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

  /**
   * The button drawn from its card design, `card-designs/ui-builder/designs/button.uid`
   * ([CardDesigns]): which form of the button shows at which size, on the dashboard, a launcher
   * cell or a Wear container, is the design's; [ButtonDesignParts] draw the forms.
   */
  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val design = requireNotNull(CardDesigns.design(cardType)) { "no button.uid packaged" }
    CompositionLocalProvider(LocalCardInput provides DesignTemplateInput(card, snapshot)) {
      design.Render(modifier)
    }
  }

  private companion object {
    val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name", "icon", "show_name")

    const val SHOW_NAME = "show_name"

    /** Narrowest `grid` cell: the compact body (44 dp chip + 12 dp sides) plus a short name. */
    const val MinGridCellWidthDp = 80
  }
}

/** Build the [HaButtonData] payload from card config + live snapshot. */
@Composable
internal fun buttonData(card: CardConfig, snapshot: HaSnapshot): HaButtonData {
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
