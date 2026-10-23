@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.HaHeadingStyle
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.RemoteHaHeading
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput

/**
 * The parts a heading card's design arranges (card-designs/ui-builder/designs/heading.uid): each
 * draws one form of the heading for the card being drawn ([LocalCardInput]), and [Cell] only says
 * which of its slots the canvas calls for. The card design renderer draws them through
 * [components]; ha-cards-catalog's `HaHeadingCard*` wrappers put the same calls on UI Builder's
 * shelf.
 */
object HeadingDesignParts {
  private val converter = HeadingCardConverter()

  /** The card being drawn, or a placeholder title in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: requireNotNull(
        converter.designTemplate(
          DesignProperties.build { string(HeadingCardConverter.HEADING_STYLE, "title") }
        )
      )

  /** `ha-cards/heading-card-title`: the heading in its style, as a dashboard draws it. */
  @Composable
  fun Title(modifier: RemoteModifier) {
    val (card, _) = input()
    RemoteHaHeading(converter.headingData(card), modifier = modifier)
  }

  /**
   * `ha-cards/heading-card-centred`: the heading centred in a fixed cell that is taller than it,
   * inset by a card's padding. A one-column launcher cell steps it down to a subtitle with a narrow
   * inset before cutting it short; a roomy one sets a title at the hero's value size.
   */
  @Composable
  fun Centred(modifier: RemoteModifier) {
    val (card, _) = input()
    val data = converter.headingData(card)
    val narrow =
      (LocalWidgetCaptureSizeDp.current?.widthDp ?: Int.MAX_VALUE) <
        HeadingCardConverter.NarrowWidthDp
    CenteredCell {
      RemoteBox(
        modifier.fillMaxWidth().padding(horizontal = (if (narrow) HaSpace.XS else HaSpace.L).rdp)
      ) {
        val hero = captureHeroScale()
        if (hero != null && data.style == HaHeadingStyle.Title)
          RemoteHaHeading(data, textSize = hero.state)
        else RemoteHaHeading(if (narrow) data.copy(style = HaHeadingStyle.Subtitle) else data)
      }
    }
  }

  /** `ha-cards/heading-card-cell`: [wrap] on a dashboard, [fixed] in a fixed canvas. */
  @Composable
  fun Cell(wrap: @Composable () -> Unit, fixed: @Composable () -> Unit) {
    if (LocalCardSizeMode.current == CardSizeMode.Fixed) fixed() else wrap()
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/heading-card-title" to { _, modifier -> Title(modifier) },
      "ha-cards/heading-card-centred" to { _, modifier -> Centred(modifier) },
      "ha-cards/heading-card-cell" to
        { node, modifier ->
          Cell(wrap = { node.Slot("wrap", modifier) }, fixed = { node.Slot("fixed", modifier) })
        },
    )
}
