package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaPictureGlanceCell
import ee.schimke.ha.rc.components.HaPictureGlanceData
import ee.schimke.ha.rc.components.RemoteHaPictureGlance
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.icons.HaIconMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** `picture-glance` card — image with a row of clickable entity cells. */
class PictureGlanceCardConverter : CardConverter {
    override val cardType: String = CardTypes.PICTURE_GLANCE

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 168

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val image = card.raw["image"]?.jsonPrimitive?.content
        val title = card.raw["title"]?.jsonPrimitive?.content

        val ids: List<String> = (card.raw["entities"] as? JsonArray)?.mapNotNull { el ->
            when (el) {
                is JsonObject -> el["entity"]?.jsonPrimitive?.content
                else -> el.jsonPrimitive.content
            }
        }.orEmpty()

        val cells = ids.map { id ->
            val entity = snapshot.states[id]
            val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id
            val active = entity?.state == "on" || entity?.state == "open"
            HaPictureGlanceCell(
                icon = HaIconMap.resolve(null, entity),
                accent = HaStateColor.activeFor(entity),
                isActive = active,
                label = name.rs,
                tapAction = defaultTapActionFor(id),
            )
        }

        RemoteHaPictureGlance(
            HaPictureGlanceData(
                title = title?.rs,
                captionUrl = image?.rs,
                placeholderIcon = Icons.Filled.Image,
                cells = cells,
            ),
            modifier = modifier,
        )
    }
}
