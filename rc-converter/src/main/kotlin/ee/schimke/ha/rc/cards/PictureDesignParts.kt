@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.components.RemoteHaPicture
import ee.schimke.ha.rc.components.RemoteHaPictureEntity
import ee.schimke.ha.rc.components.RemoteHaPictureGlance
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * The parts the picture cards' designs arrange (card-designs/ui-builder/designs/`picture.uid`,
 * `picture-entity.uid` and `picture-glance.uid`): each draws one form of its card for the card
 * being drawn ([LocalCardInput]); which form a surface gets is the design's `card-size-mode` and
 * `size-tiers`. The card design renderer draws them through [components]; ha-cards-catalog's
 * `HaPicture*Card*` wrappers put the same calls on UI Builder's shelf.
 */
object PictureDesignParts {
  private val picture = PictureCardConverter()
  private val pictureEntity = PictureEntityCardConverter()
  private val pictureGlance = PictureGlanceCardConverter()

  /** The card being drawn, or [placeholder] in UI Builder's canvas. */
  @Composable
  private fun input(placeholder: () -> CardConfig): DesignTemplateInput =
    LocalCardInput.current ?: DesignTemplateInput(placeholder(), HaSnapshot())

  private fun card(type: String, body: Map<String, kotlinx.serialization.json.JsonElement>) =
    CardConfig(type, JsonObject(mapOf("type" to JsonPrimitive(type)) + body))

  /**
   * `ha-cards/picture-card-image`: the picture with its name; [fill] fills the canvas edge to edge
   * (a launcher or Wear cell), otherwise the image keeps the card's `aspect_ratio`.
   */
  @Composable
  fun Picture(fill: Boolean, modifier: RemoteModifier) {
    val (card, _) = input { card("picture", mapOf("name" to JsonPrimitive("Garden"))) }
    RemoteHaPicture(
      picture.pictureData(card),
      modifier = modifier,
      fillHeight = fill,
      aspectRatio = if (fill) null else pictureAspectRatio(card),
    )
  }

  /**
   * `ha-cards/picture-entity-card-image`: the entity's picture under its name and state strip;
   * [fill] fills the canvas, otherwise the image keeps the card's `aspect_ratio`; [hideName] drops
   * the name so the state badge alone reads.
   */
  @Composable
  fun PictureEntity(fill: Boolean, hideName: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) =
      input { card("picture-entity", mapOf("entity" to JsonPrimitive("camera.front_door"))) }
    val data = pictureEntity.pictureEntityData(card, snapshot)
    val shown = if (hideName) data.copy(showName = false) else data
    if (fill) RemoteHaPictureEntity(shown, modifier = modifier, fillHeight = true)
    else RemoteHaPictureEntity(shown, modifier = modifier, aspectRatio = pictureAspectRatio(card))
  }

  /**
   * `ha-cards/picture-glance-card-image`: the image under its strip of entity cells; [fill] fills
   * the canvas, the image taking what the strip leaves, otherwise the image keeps the card's
   * `aspect_ratio`; [showImage] off keeps the strip alone.
   */
  @Composable
  fun PictureGlance(fill: Boolean, showImage: Boolean, modifier: RemoteModifier) {
    val (card, snapshot) =
      input {
        card(
          "picture-glance",
          mapOf(
            "title" to JsonPrimitive("Garden"),
            "entities" to
              buildJsonArray {
                add(JsonPrimitive("light.porch"))
                add(JsonPrimitive("lock.gate"))
              },
          ),
        )
      }
    val data = pictureGlance.pictureGlanceData(card, snapshot)
    if (fill) RemoteHaPictureGlance(data, modifier, fillHeight = true, showImage = showImage)
    else
      RemoteHaPictureGlance(data, modifier = modifier, imageAspectRatio = pictureAspectRatio(card))
  }

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/picture-card-image" to { node, modifier -> Picture(node.bool("fill"), modifier) },
      "ha-cards/picture-entity-card-image" to
        { node, modifier ->
          PictureEntity(node.bool("fill"), node.bool("hideName"), modifier)
        },
      "ha-cards/picture-glance-card-image" to
        { node, modifier ->
          PictureGlance(node.bool("fill"), node.bool("showImage", true), modifier)
        },
    )
}
