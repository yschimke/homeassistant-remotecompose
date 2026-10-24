@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.components.HaGlanceData
import ee.schimke.ha.rc.components.RemoteHaGlance
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The parts a glance card's design arranges (card-designs/ui-builder/designs/glance.uid): each
 * draws one form of the card for the card being drawn ([LocalCardInput]), and [Cell] only says
 * which of its slots the card and its canvas call for. The card design renderer draws them through
 * [components]; ha-cards-catalog's `HaGlanceCard*` wrappers put the same calls on UI Builder's
 * shelf.
 */
object GlanceDesignParts {
  private val converter = GlanceCardConverter()

  /** The card being drawn, or a placeholder glance over two lights in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          CardTypes.GLANCE,
          buildJsonObject {
            put("type", CardTypes.GLANCE)
            put("title", "Lights")
            put(
              "entities",
              buildJsonArray {
                add(JsonPrimitive("light.kitchen"))
                add(JsonPrimitive("light.hall"))
              },
            )
          },
        ),
        HaSnapshot(),
      )

  /** `ha-cards/glance-card-grid`: the title over the grid of cells, fitted to a launcher cell. */
  @Composable
  fun Grid(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    converter.Grid(card, snapshot, modifier)
  }

  /**
   * `ha-cards/glance-card-cells`: the cells without the title, filling the canvas; [compact] drops
   * each cell's name, keeping its icon over its state.
   */
  @Composable
  fun Cells(compact: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) = input()
    RemoteHaGlance(
      HaGlanceData(title = null, cells = converter.cellsOf(card, snapshot)),
      modifier = modifier,
      fillHeight = true,
      compactCells = compact,
    )
  }

  /**
   * `ha-cards/glance-card-first-tile`: the first entity as a tile, for a one-cell launcher list.
   */
  @Composable
  fun FirstTile(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    val tile = compactLauncherTile(card) ?: return
    TileCardConverter().Render(tile, snapshot, modifier)
  }

  /**
   * `ha-cards/glance-card-cell`: which slot the card and its canvas call for: [tile] in a launcher
   * cell too narrow for a list ([compactLauncherTile]), [wear] in a canvas whose height decides (a
   * Wear container), [other] otherwise.
   */
  @Composable
  fun Cell(
    tile: @Composable () -> Unit,
    wear: @Composable () -> Unit,
    other: @Composable () -> Unit,
  ) {
    val (card, _) = input()
    when {
      compactLauncherTile(card) != null -> tile()
      LocalFixedCanvasAxis.current == BreakpointAxis.Height -> wear()
      else -> other()
    }
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/glance-card-grid" to { _, modifier -> Grid(modifier) },
      "ha-cards/glance-card-cells" to { node, modifier -> Cells(node.bool("compact"), modifier) },
      "ha-cards/glance-card-first-tile" to { _, modifier -> FirstTile(modifier) },
      "ha-cards/glance-card-cell" to
        { node, modifier ->
          Cell(
            tile = { node.Slot("tile", modifier) },
            wear = { node.Slot("wear", modifier) },
            other = { node.Slot("other", modifier) },
          )
        },
    )
}
