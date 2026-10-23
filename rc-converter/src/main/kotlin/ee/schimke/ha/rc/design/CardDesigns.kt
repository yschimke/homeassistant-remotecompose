@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.design

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.RemoteMinSizeGate
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.cards.ButtonDesignParts
import ee.schimke.ha.rc.cards.CenteredCell
import ee.schimke.ha.rc.cards.EntityDesignParts
import ee.schimke.ha.rc.cards.SensorDesignParts
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
   * - `ha-cards/min-size-gate`: `roomy` where the canvas is at least `minWidthDp` × `minHeightDp`,
   *   `small` where it isn't, picked where the document plays.
   */
  internal val structure: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/dashboard-card" to { node, modifier -> node.Slot("content", modifier) },
      "ha-cards/card-size-mode" to
        { node, modifier ->
          SizeModeSwitch(
            wrap = { node.Slot("wrap", modifier) },
            height = { node.Slot("height", modifier) },
            width = { node.Slot("width", modifier) },
          )
        },
      "ha-cards/size-tiers" to
        { node, modifier ->
          SizeTiers(node.string("thresholdsDp"), node.string("axis", "width"), modifier) { tier ->
            node.Slot("tier$tier")
          }
        },
      "ha-cards/centered-cell" to { node, modifier -> Centered(modifier) { node.Slot("content") } },
      "ha-cards/min-size-gate" to
        { node, modifier ->
          MinSizeGate(node.int("minWidthDp", 96), node.int("minHeightDp", 80), modifier) { roomy ->
            node.Slot(if (roomy) "roomy" else "small")
          }
        },
    )

  /** `ha-cards/card-size-mode`: [wrap], [height] or [width] for the surface being drawn. */
  @Composable
  fun SizeModeSwitch(
    wrap: @Composable () -> Unit,
    height: @Composable () -> Unit,
    width: @Composable () -> Unit,
  ) {
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> wrap()
      CardSizeMode.Fixed ->
        when (LocalFixedCanvasAxis.current) {
          BreakpointAxis.Height -> height()
          BreakpointAxis.Width -> width()
        }
    }
  }

  /**
   * `ha-cards/size-tiers`: [tier] for the canvas's size along [axis] (`width` or `height`) against
   * the ascending, comma-separated [thresholdsDp].
   */
  @Composable
  fun SizeTiers(
    thresholdsDp: String,
    axis: String,
    modifier: RemoteModifier,
    tier: @Composable (Int) -> Unit,
  ) {
    RemoteSizeBreakpoint(
      thresholdsDp = thresholdsDp.split(',').map { it.trim().toInt() }.toIntArray(),
      modifier = modifier,
      axis = if (axis == "height") BreakpointAxis.Height else BreakpointAxis.Width,
      content = tier,
    )
  }

  /**
   * `ha-cards/min-size-gate`: [content] `roomy` where the canvas is at least [minWidthDp] ×
   * [minHeightDp], picked where the document plays.
   */
  @Composable
  fun MinSizeGate(
    minWidthDp: Int,
    minHeightDp: Int,
    modifier: RemoteModifier,
    content: @Composable (roomy: Boolean) -> Unit,
  ) {
    RemoteMinSizeGate(minWidthDp, minHeightDp, modifier, content)
  }

  /**
   * `ha-cards/centered-cell`: [content] centred vertically in a fixed cell, the cell sized by
   * [modifier] when the design gives it one.
   */
  @Composable
  fun Centered(modifier: RemoteModifier, content: @Composable () -> Unit) {
    if (modifier === RemoteModifier) CenteredCell(content)
    else RemoteBox(modifier) { CenteredCell(content) }
  }

  /** Every component a card design drawn in this module can use. */
  val components: Map<String, CardDesignComponent> =
    structure +
      TileDesignParts.components +
      ButtonDesignParts.components +
      EntityDesignParts.components +
      SensorDesignParts.components

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
