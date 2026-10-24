@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The parts the entities card's design arranges (card-designs/ui-builder/designs/`entities.uid`):
 * each draws one form of the list being drawn ([LocalCardInput]), and [Cell] says whether a
 * launcher cell is too narrow for a list; which form a surface gets otherwise is the design's
 * `card-size-mode`. The card design renderer draws them through [components]; ha-cards-catalog's
 * `HaEntitiesCard*` wrappers put the same calls on UI Builder's shelf.
 */
object EntitiesDesignParts {
  private val converter = EntitiesCardConverter()

  /** The card being drawn, or a list of two lights in UI Builder's canvas. */
  @Composable
  private fun input(): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          CardTypes.ENTITIES,
          buildJsonObject {
            put("type", CardTypes.ENTITIES)
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

  /** `ha-cards/entities-card-full`: the title over every row, as a dashboard draws it. */
  @Composable
  fun Full(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    converter.FullList(card, snapshot, modifier, maxRows = Int.MAX_VALUE)
  }

  /** `ha-cards/entities-card-wear`: the rows a Wear container holds, sharing its height. */
  @Composable
  fun Wear(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    converter.WearList(card, snapshot, modifier)
  }

  /** `ha-cards/entities-card-launcher`: the rows a launcher cell holds, fitted to it. */
  @Composable
  fun Launcher(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    converter.LauncherList(card, snapshot, modifier)
  }

  /** `ha-cards/entities-card-first-tile`: the first entity as a tile, for a one-cell list. */
  @Composable
  fun FirstTile(modifier: RemoteModifier) {
    val (card, snapshot) = input()
    val tile = compactLauncherTile(card) ?: return
    TileCardConverter().Render(tile, snapshot, modifier)
  }

  /**
   * `ha-cards/entities-card-cell`: [tile] in a launcher cell too narrow for a list
   * ([compactLauncherTile]), [other] otherwise.
   */
  @Composable
  fun Cell(tile: @Composable () -> Unit, other: @Composable () -> Unit) {
    val (card, _) = input()
    if (compactLauncherTile(card) != null) tile() else other()
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/entities-card-full" to { _, modifier -> Full(modifier) },
      "ha-cards/entities-card-wear" to { _, modifier -> Wear(modifier) },
      "ha-cards/entities-card-launcher" to { _, modifier -> Launcher(modifier) },
      "ha-cards/entities-card-first-tile" to { _, modifier -> FirstTile(modifier) },
      "ha-cards/entities-card-cell" to
        { node, modifier ->
          Cell(tile = { node.Slot("tile", modifier) }, other = { node.Slot("other", modifier) })
        },
    )
}
