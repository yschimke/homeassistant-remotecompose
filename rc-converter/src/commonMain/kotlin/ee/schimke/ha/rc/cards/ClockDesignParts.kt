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
import ee.schimke.ha.rc.LocalWidgetCaptureSizeDp
import ee.schimke.ha.rc.components.HaClockData
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
    val data = converter.clockData(card)
    val narrow = (LocalWidgetCaptureSizeDp.current?.widthDp ?: Int.MAX_VALUE) < CLOCK_NARROW_DP
    RemoteHaClock(
      data,
      modifier = modifier,
      compact = compact,
      timeSizeSp = launcherTimeSize(data, narrow),
      narrow = narrow,
    )
  }

  /**
   * The time's size on a launcher widget: as large as the widget's width holds the longest time the
   * clock can show (seconds, an AM/PM suffix where the hour format isn't fixed) and its height
   * holds beside the title and date, so one column isn't cut to "1" and a big widget isn't a small
   * clock in a blank cell. Null off a launcher, where the design's size stands.
   */
  @Composable
  private fun launcherTimeSize(data: HaClockData, narrow: Boolean): Int? {
    val cell = LocalWidgetCaptureSizeDp.current ?: return null
    val chars = 5 + (if (data.showSeconds) 3 else 0) + (if (data.use24Hour) 0 else 3)
    val lines =
      if (narrow) 0
      else (if (data.title != null) 1 else 0) + (if (data.secondaryLabel != null) 1 else 0)
    val padding = if (narrow) CLOCK_NARROW_PADDING_DP else CLOCK_PADDING_DP
    val byWidth = (cell.widthDp - padding) / (chars * CLOCK_EM_PER_CHAR)
    val byHeight = (cell.heightDp - CLOCK_PADDING_DP - lines * CLOCK_LINE_DP) * CLOCK_EM_PER_LINE
    return minOf(byWidth, byHeight).toInt().coerceIn(CLOCK_MIN_SP, CLOCK_MAX_SP)
  }

  // A Light digit or colon is about 0.6 em wide, and a line of type 1.2 em tall; the face pads 14
  // dp a side and 12 above and below, and a title or date line is about 18 dp.
  private const val CLOCK_EM_PER_CHAR = 0.62f
  private const val CLOCK_EM_PER_LINE = 0.8f
  private const val CLOCK_PADDING_DP = 30
  private const val CLOCK_LINE_DP = 20
  private const val CLOCK_NARROW_PADDING_DP = 10
  private const val CLOCK_NARROW_DP = 100
  private const val CLOCK_MIN_SP = 10
  private const val CLOCK_MAX_SP = 120

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
