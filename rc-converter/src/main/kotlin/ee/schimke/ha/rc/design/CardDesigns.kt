@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.design

import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cards.CenteredCell
import ee.schimke.ha.rc.cards.TileDesignParts
import java.util.concurrent.ConcurrentHashMap

/**
 * The card designs card types are drawn from (server-owned-card-designs.md): each migrated card
 * type's checked-in `card-designs/ui-builder/designs/<cardType>.uid`, packaged with this module,
 * drawn with [components]. A converter whose layout lives in its design draws its card with
 * [design]; the design server edits the same file, and the scheduled import brings edits back.
 */
object CardDesigns {
  /**
   * The structural components every card design can use: the card root, and the switches that pick
   * what a card draws for the surface and size it is drawn at.
   * - `ha-cards/dashboard-card`: the card itself; its `content` is drawn with the card's modifier.
   * - `ha-cards/card-size-mode`: `wrap` on a dashboard (the card sizes itself), `height` in a fixed
   *   canvas whose height decides (a Wear widget container), `width` in one whose width decides (a
   *   launcher cell).
   * - `ha-cards/size-tiers`: tier `tier<N>` for the canvas's size along `axis` (`width` or
   *   `height`) against the ascending `thresholdsDp` (comma-separated), picked where the document
   *   plays.
   * - `ha-cards/centered-cell`: `content` centred vertically in a fixed cell.
   */
  val structure: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/dashboard-card" to { node, modifier -> node.Slot("content", modifier) },
      "ha-cards/card-size-mode" to
        { node, modifier ->
          when (LocalCardSizeMode.current) {
            CardSizeMode.Wrap -> node.Slot("wrap", modifier)
            CardSizeMode.Fixed ->
              when (LocalFixedCanvasAxis.current) {
                BreakpointAxis.Height -> node.Slot("height", modifier)
                BreakpointAxis.Width -> node.Slot("width", modifier)
              }
          }
        },
      "ha-cards/size-tiers" to
        { node, modifier ->
          val thresholds =
            node.string("thresholdsDp").split(',').map { it.trim().toInt() }.toIntArray()
          val axis =
            if (node.string("axis", "width") == "height") BreakpointAxis.Height
            else BreakpointAxis.Width
          RemoteSizeBreakpoint(thresholdsDp = thresholds, modifier = modifier, axis = axis) { tier
            ->
            node.Slot("tier$tier")
          }
        },
      "ha-cards/centered-cell" to { node, _ -> CenteredCell { node.Slot("content") } },
    )

  /** Every component a card design drawn in this module can use. */
  val components: Map<String, CardDesignComponent> = structure + TileDesignParts.components

  private val loaded = ConcurrentHashMap<String, Result<CardDesign?>>()

  /** [cardType]'s checked-in design, or null when its layout still lives in its converter. */
  fun design(cardType: String): CardDesign? =
    loaded
      .getOrPut(cardType) {
        runCatching {
          CardDesigns::class.java.getResourceAsStream("/$RESOURCE_DIR/$cardType.uid")?.use {
            CardDesign.parse(it.readBytes().decodeToString(), components)
          }
        }
      }
      .getOrThrow()

  /** Where the checked-in designs are packaged: `card-designs/ui-builder/designs`. */
  const val RESOURCE_DIR = "designs"
}
