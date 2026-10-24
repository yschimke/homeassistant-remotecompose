@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaIconIdentity
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaWideHero
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import ee.schimke.ha.rc.framedDesignTemplate
import kotlinx.serialization.json.jsonPrimitive

/**
 * The parts an entity card's design arranges (card-designs/ui-builder/designs/entity.uid): each
 * draws one form of the card for the card being drawn ([LocalCardInput]). Which form shows at which
 * size, on which surface, is the design's: these only draw, and [Cell] only says which of its slots
 * the canvas calls for. The card design renderer draws them through [components];
 * ha-cards-catalog's `HaEntityCard*` wrappers put the same calls on UI Builder's shelf.
 */
object EntityDesignParts {
  private val converter = EntityCardConverter()

  /** The card being drawn, or a placeholder light entity in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: requireNotNull(
        converter.framedDesignTemplate(
          DesignProperties.build { string(EntityDesign.DOMAIN, "light") }
        )
      )

  /**
   * `ha-cards/entity-card-row`: the entity's icon, name and state in a row. [inset] `card` insets
   * it by a card's horizontal padding, for a cell that is the card (its value would otherwise meet
   * the widget's edge); `none` doesn't.
   */
  @Composable
  fun Row(inset: String, modifier: RemoteModifier) {
    val (card, snapshot) = input()
    converter.FullRow(
      card,
      snapshot,
      if (inset == "card") modifier.padding(horizontal = HaSpace.L.rdp) else modifier,
    )
  }

  /**
   * `ha-cards/entity-card-hero`: a roomy launcher cell's form. A numeric reading takes its
   * measurement's expanded presentation, any other entity the icon hero at the cell's scale.
   */
  @Composable
  fun Hero(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    converter.Hero(card, snapshot, captureHeroScale() ?: WearHeroScale, modifier)
  }

  /**
   * `ha-cards/entity-card-wide-hero`: a big icon beside name and state. [scale] `row` sizes it for
   * a one-row launcher cell (the Wear scale outside one); `wear` for a Wear container, not live.
   */
  @Composable
  fun WideHero(scale: String, modifier: RemoteModifier) {
    val (card, snapshot) = input()
    val data = converter.iconChipData(card, snapshot)
    if (scale == "wear") RemoteHaWideHero(data, WearHeroScale, modifier, live = false)
    else RemoteHaWideHero(data, captureRowScale() ?: WearHeroScale, modifier)
  }

  /**
   * `ha-cards/entity-card-icon-chip`: the icon chip and state, for a cell too narrow for the row.
   * The name stays under the chip unless the icon is evocative ([HaIconIdentity]).
   */
  @Composable
  fun IconChip(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    RemoteHaIconChip(
      converter.iconChipData(card, snapshot),
      modifier,
      bindState = LocalWidgetCaptureSizeDp.current != null,
      showName =
        HaIconIdentity.chipShowsName(
          card,
          card.raw["entity"]?.jsonPrimitive?.content?.let { snapshot.states[it] },
        ),
    )
  }

  /**
   * `ha-cards/entity-card-cell`: which slot the canvas calls for. On a dashboard, [wrap]. In a
   * fixed canvas: [hero] or [row] for a launcher cell with a hero scale; [wear] in a canvas whose
   * height decides (a Wear container); [other] otherwise.
   */
  @Composable
  fun Cell(
    wrap: @Composable () -> Unit,
    hero: @Composable () -> Unit,
    row: @Composable () -> Unit,
    wear: @Composable () -> Unit,
    other: @Composable () -> Unit,
  ) {
    when {
      LocalCardSizeMode.current == CardSizeMode.Wrap -> wrap()
      captureHeroScale() != null -> hero()
      captureRowScale() != null -> row()
      LocalFixedCanvasAxis.current == BreakpointAxis.Height -> wear()
      else -> other()
    }
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/entity-card-row" to
        { node, modifier ->
          Row(node.string("inset", "none"), modifier)
        },
      "ha-cards/entity-card-hero" to { _, modifier -> Hero(modifier) },
      "ha-cards/entity-card-wide-hero" to
        { node, modifier ->
          WideHero(node.string("scale", "row"), modifier)
        },
      "ha-cards/entity-card-icon-chip" to { _, modifier -> IconChip(modifier) },
      "ha-cards/entity-card-cell" to
        { node, modifier ->
          Cell(
            wrap = { node.Slot("wrap", modifier) },
            hero = { node.Slot("hero", modifier) },
            row = { node.Slot("row", modifier) },
            wear = { node.Slot("wear", modifier) },
            other = { node.Slot("other", modifier) },
          )
        },
    )
}
