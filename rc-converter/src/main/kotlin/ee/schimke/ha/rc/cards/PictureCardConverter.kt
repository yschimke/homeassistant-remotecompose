package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.components.HaPictureCardData
import ee.schimke.ha.rc.components.RemoteHaPicture
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `picture` card — static image URL. RC has no media-image channel
 *  yet; renderer paints a placeholder + caption. */
class PictureCardConverter : CardConverter {
    override val cardType: String = CardTypes.PICTURE

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 140

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
