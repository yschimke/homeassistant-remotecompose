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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts of the container cards (card-designs/ui-builder/designs/`vertical-stack.uid`,
 * `horizontal-stack.uid` and `grid.uid`): each lays out the container being drawn
 * ([LocalCardInput]) as its converter does, its children drawn from their own designs. The card
 * design renderer draws them through [components]; ha-cards-catalog's wrappers put the same calls
 * on UI Builder's shelf.
 */
object ContainerDesignParts {
  private val vertical = VerticalStackCardConverter()
  private val horizontal = HorizontalStackCardConverter()
  private val grid = GridCardConverter()

  /** The container being drawn, or an empty [type] in UI Builder's canvas. */
  @Composable
  private fun input(type: String): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(
          type,
          JsonObject(mapOf("type" to JsonPrimitive(type), "cards" to JsonArray(emptyList()))),
        ),
        HaSnapshot(),
      )

  /** `ha-cards/vertical-stack-card`: the children, one under another. */
  @Composable
  fun VerticalStack(modifier: RemoteModifier) {
    val (card, snapshot) = input(CardTypes.VERTICAL_STACK)
    vertical.Draw(card, snapshot, modifier)
  }

  /** `ha-cards/horizontal-stack-card`: the children side by side, each a column's width. */
  @Composable
  fun HorizontalStack(modifier: RemoteModifier) {
    val (card, snapshot) = input(CardTypes.HORIZONTAL_STACK)
    horizontal.Draw(card, snapshot, modifier)
  }

  /** `ha-cards/grid-card`: the children in as many columns as the grid's width holds. */
  @Composable
  fun Grid(modifier: RemoteModifier) {
    val (card, snapshot) = input(CardTypes.GRID)
    grid.Draw(card, snapshot, modifier)
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/vertical-stack-card" to { _, modifier -> VerticalStack(modifier) },
      "ha-cards/horizontal-stack-card" to { _, modifier -> HorizontalStack(modifier) },
      "ha-cards/grid-card" to { _, modifier -> Grid(modifier) },
    )
}
