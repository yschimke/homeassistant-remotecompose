@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaIconIdentity
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalGridSquareCells
import ee.schimke.ha.rc.LocalInCardContainer
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.RemoteHaButton
import ee.schimke.ha.rc.components.RemoteHaButtonIcon
import ee.schimke.ha.rc.components.RemoteHaButtonRow
import ee.schimke.ha.rc.components.RemoteHaIconHero
import ee.schimke.ha.rc.components.RemoteHaToggleButton
import ee.schimke.ha.rc.components.RemoteHaWideHero
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import ee.schimke.ha.rc.framedDesignTemplate

/**
 * The parts a button's card design arranges (card-designs/ui-builder/designs/button.uid): each
 * draws one form of the button for the card being drawn ([LocalCardInput]), which the button's data
 * (name, state, icon, accent, what `show_name`, `show_icon` and `show_state` leave in) comes from.
 * Which form shows at which size, on which surface, is the design's: these only draw, and [Cell]
 * only says which of its slots the card and its canvas call for. The card design renderer draws
 * them through [components]; ha-cards-catalog's `HaButton*` wrappers put the same calls on UI
 * Builder's shelf.
 */
object ButtonDesignParts {
  private val converter = ButtonCardConverter()

  /** The card being drawn, or a placeholder light button in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: requireNotNull(
        converter.framedDesignTemplate(
          DesignProperties.build { string(EntityDesign.DOMAIN, "light") }
        )
      )

  @Composable private fun data() = input().let { (card, snapshot) -> buttonData(card, snapshot) }

  /**
   * `ha-cards/button-full`: the button, its icon over its name (and state, when shown); a toggle
   * when its entity is one. [compact] `grid` is the compact body in a square grid cell only, `true`
   * always, `false` never.
   */
  @Composable
  fun Full(compact: String, modifier: RemoteModifier) {
    val data = data()
    val isCompact =
      when (compact) {
        "true" -> true
        "false" -> false
        else -> LocalGridSquareCells.current
      }
    if (data.accent.toggleable) RemoteHaToggleButton(data, modifier, isCompact)
    else RemoteHaButton(data, modifier, isCompact)
  }

  /**
   * `ha-cards/button-icon`: the button's icon chip, for a cell too small for the full button. The
   * name stays under the chip at label size unless the card hides it or the icon is evocative
   * ([HaIconIdentity], card-information-hierarchy.md).
   */
  @Composable
  fun Icon(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    val data = buttonData(card, snapshot)
    val entity = data.entityId?.let { snapshot.states[it] }
    RemoteHaButtonIcon(
      data,
      modifier,
      showName = data.showName && HaIconIdentity.chipShowsName(card, entity),
    )
  }

  /**
   * `ha-cards/button-name-row`: the icon beside the name, for a cell too short for the name under
   * the icon; the icon alone when the card hides its name.
   */
  @Composable
  fun NameRow(modifier: RemoteModifier) {
    val data = data()
    if (data.showName) RemoteHaButtonRow(data, modifier) else RemoteHaButtonIcon(data, modifier)
  }

  /** `ha-cards/button-icon-hero`: a big icon over name and state, at the launcher cell's scale. */
  @Composable
  fun IconHero(modifier: RemoteModifier) {
    val data = data()
    RemoteHaIconHero(
      data.asTile(),
      captureHeroScale() ?: WearHeroScale,
      modifier,
      showState = data.state != null,
    )
  }

  /** `ha-cards/button-wide-hero`: a big icon beside name and state, for a one-row launcher cell. */
  @Composable
  fun WideHero(modifier: RemoteModifier) {
    val data = data()
    val row = captureRowScale() ?: WearHeroScale
    RemoteHaWideHero(
      data.asTile(),
      row.copy(name = row.state),
      modifier,
      showState = data.state != null,
    )
  }

  /**
   * `ha-cards/button-cell`: which slot the card and its canvas call for. On a dashboard, [wrap]. In
   * a fixed canvas: [fill] when the card hides its icon (nothing is left for a small form); [row]
   * or [hero] for a launcher cell with a hero scale when the card shows its name; [wear] in a Wear
   * container (a canvas whose height decides, not inside a stack or grid); [other] otherwise.
   */
  @Composable
  fun Cell(
    wrap: @Composable () -> Unit,
    fill: @Composable () -> Unit,
    row: @Composable () -> Unit,
    hero: @Composable () -> Unit,
    wear: @Composable () -> Unit,
    other: @Composable () -> Unit,
  ) {
    if (LocalCardSizeMode.current == CardSizeMode.Wrap) return wrap()
    val data = data()
    when {
      !data.showIcon -> fill()
      captureRowScale() != null && data.showName -> row()
      captureHeroScale() != null && data.showName -> hero()
      LocalFixedCanvasAxis.current == BreakpointAxis.Height && !LocalInCardContainer.current ->
        wear()
      else -> other()
    }
  }

  private fun HaButtonData.asTile() =
    HaTileData(
      entityId = entityId,
      name = name,
      state = state ?: "".rs,
      icon = icon,
      accent = accent,
      tapAction = tapAction,
    )

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/button-full" to
        { node, modifier ->
          Full(node.string("compact", "grid"), modifier)
        },
      "ha-cards/button-icon" to { _, modifier -> Icon(modifier) },
      "ha-cards/button-name-row" to { _, modifier -> NameRow(modifier) },
      "ha-cards/button-icon-hero" to { _, modifier -> IconHero(modifier) },
      "ha-cards/button-wide-hero" to { _, modifier -> WideHero(modifier) },
      "ha-cards/button-cell" to
        { node, modifier ->
          Cell(
            wrap = { node.Slot("wrap", modifier) },
            fill = { node.Slot("fill", modifier) },
            row = { node.Slot("row", modifier) },
            hero = { node.Slot("hero", modifier) },
            wear = { node.Slot("wear", modifier) },
            other = { node.Slot("other", modifier) },
          )
        },
    )
}
