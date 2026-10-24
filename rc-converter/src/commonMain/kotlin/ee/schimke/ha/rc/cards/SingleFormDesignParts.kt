@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts of the cards with one form on every surface (card-designs/ui-builder/designs/`map.uid`
 * and `picture-elements.uid`): each draws its card being drawn ([LocalCardInput]) as its converter
 * does. The card design renderer draws them through [components]; ha-cards-catalog's wrappers put
 * the same calls on UI Builder's shelf.
 */
object SingleFormDesignParts {
  private val map = MapCardConverter()
  private val pictureElements = PictureElementsCardConverter()

  /** The card being drawn, or a [type] card in UI Builder's canvas. */
  @Composable
  private fun input(type: String): DesignTemplateInput =
    LocalCardInput.current
      ?: DesignTemplateInput(
        CardConfig(type, JsonObject(mapOf("type" to JsonPrimitive(type)))),
        HaSnapshot(),
      )

  /** `ha-cards/map-card`: the title over a row per tracked entity. */
  @Composable
  fun Map(modifier: RemoteModifier) {
    val (card, snapshot) = input("map")
    map.Draw(card, snapshot, modifier)
  }

  /** `ha-cards/picture-elements-card`: the picture with its elements over it. */
  @Composable
  fun PictureElements(modifier: RemoteModifier) {
    val (card, snapshot) = input("picture-elements")
    pictureElements.Draw(card, snapshot, modifier)
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/map-card" to { _, modifier -> Map(modifier) },
      "ha-cards/picture-elements-card" to { _, modifier -> PictureElements(modifier) },
    )
}
