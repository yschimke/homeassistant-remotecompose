@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.components.RemoteHaClock
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput

/**
 * The parts a clock card's design arranges (card-designs/ui-builder/designs/clock.uid): each draws
 * one form of the clock for the card being drawn ([LocalCardInput]), and [Cell] only says which of
 * its slots the canvas calls for. The card design renderer draws them through [components];
 * ha-cards-catalog's `HaClockCard*` wrappers put the same calls on UI Builder's shelf.
 */
object ClockDesignParts {
  private val converter = ClockCardConverter()

  /** The card being drawn, or a titled 24-hour clock with its date in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: requireNotNull(
        converter.designTemplate(
          DesignProperties.build {
            enum("has_title", 1)
            enum("h24", 1)
            enum("date", 1)
          }
        )
      )

  /** `ha-cards/clock-card-face`: the time, its title and date line; [compact] is the time alone. */
  @Composable
  fun Face(compact: Boolean, modifier: RemoteModifier) {
    val (card, _) = input()
    RemoteHaClock(converter.clockData(card), modifier = modifier, compact = compact)
  }

  /**
   * `ha-cards/clock-card-cell`: [wear] in a fixed canvas whose height decides (a Wear container),
   * [other] otherwise.
   */
  @Composable
  fun Cell(wear: @Composable () -> Unit, other: @Composable () -> Unit) {
    if (
      LocalCardSizeMode.current == CardSizeMode.Fixed &&
        LocalFixedCanvasAxis.current == BreakpointAxis.Height
    )
      wear()
    else other()
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/clock-card-face" to { node, modifier -> Face(node.bool("compact"), modifier) },
      "ha-cards/clock-card-cell" to
        { node, modifier ->
          Cell(wear = { node.Slot("wear", modifier) }, other = { node.Slot("other", modifier) })
        },
    )
}
