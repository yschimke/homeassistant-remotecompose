package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.components.HaPictureCardData
import ee.schimke.ha.rc.components.RemoteHaPicture
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `picture` card — static image URL. RC has no media-image channel yet; renderer paints a
 * placeholder + caption.
 */
class PictureCardConverter : CardConverter {
  override val cardType: String = CardTypes.PICTURE

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 140

  /**
   * A picture card renders from a card design: its name and image caption are `card.name` and
   * `card.image`, so only which of them it has is structure. Its tap is the card's own.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!setOf("type", "image", "name", "tap_action").containsAll(card.raw.keys)) return null
    return DesignProperties.build {
      enum("has_name", if (card.raw["name"] != null) 1 else 0)
      enum("has_image", if (card.raw["image"] != null) 1 else 0)
      enum("has_tap", if (cardTap(card) != ee.schimke.ha.rc.components.HaAction.None) 1 else 0)
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    if (properties["has_name"] == null) return null
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            if (properties["has_name"] == "1") put("name", "Name")
            if (properties["has_image"] == "1") put("image", "image")
            // A tap marker, so the template is clickable; playback runs the card's own tap.
            if (properties["has_tap"] == "1") {
              put(
                "tap_action",
                buildJsonObject {
                  put("action", "navigate")
                  put("navigation_path", CARD_TAP)
                },
              )
            }
          },
      )
    return DesignTemplateInput(card, HaSnapshot())
  }

  /** The template's tap marker: the card's own configured tap. */
  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: ee.schimke.ha.rc.components.HaAction,
  ): ee.schimke.ha.rc.components.HaAction? =
    if ((templateAction as? ee.schimke.ha.rc.components.HaAction.Navigate)?.path == CARD_TAP) {
      cardTap(card)
    } else null

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CardSnapshotBindings(
      buildMap {
        card.raw["name"]?.jsonPrimitive?.content?.let { put("card.name", it) }
        card.raw["image"]?.jsonPrimitive?.content?.let { put("card.image", it) }
      },
      emptyMap(),
      emptyMap(),
      emptyMap(),
    )

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val image = card.raw["image"]?.jsonPrimitive?.content
    val name = card.raw["name"]?.jsonPrimitive?.content
    val tap = parseHaAction(card.raw["tap_action"]?.jsonObject, defaultEntityId = null)
    // The image is the identity: dashboard keeps the HA reference
    // band; launcher / Wear fill the whole cell so the picture (the
    // placeholder until a host wires an image channel) is edge-to-edge
    // with the name as a bottom scrim, never letterboxed.
    RemoteHaPicture(
      HaPictureCardData(
        name = name,
        captionUrl = image,
        placeholderIcon = Icons.Filled.Image,
        tapAction = tap,
      ),
      modifier = modifier,
      fillHeight = LocalCardSizeMode.current == CardSizeMode.Fixed,
    )
  }
}

private const val CARD_TAP = "#card"

private fun cardTap(card: CardConfig): ee.schimke.ha.rc.components.HaAction =
  parseHaAction(card.raw["tap_action"]?.jsonObject, defaultEntityId = null)
