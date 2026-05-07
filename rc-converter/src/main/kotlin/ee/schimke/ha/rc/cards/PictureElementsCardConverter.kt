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
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaPictureElement
import ee.schimke.ha.rc.components.HaPictureElementsData
import ee.schimke.ha.rc.components.RemoteHaPictureElements
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `picture-elements` card. Each element in the config has a `type:` —
 * we map state-icon / state-label / service-button / icon into the
 * corresponding [HaPictureElement] variant. Position metadata
 * (`style.top`, `style.left`) is parsed but not rendered yet — v1 lays
 * everything out as a chip strip below the image.
 */
class PictureElementsCardConverter : CardConverter {
    override val cardType: String = CardTypes.PICTURE_ELEMENTS

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 180

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val image = card.raw["image"]?.jsonPrimitive?.content
        val arr = card.raw["elements"] as? JsonArray ?: JsonArray(emptyList())
        val elements = arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            mapElement(obj, snapshot)
        }
        RemoteHaPictureElements(
            HaPictureElementsData(
                captionUrl = image?.rs,
                placeholderIcon = Icons.Filled.Image,
                elements = elements,
            ),
            modifier = modifier,
        )
    }
}

private fun mapElement(obj: JsonObject, snapshot: HaSnapshot): HaPictureElement? {
    val type = obj["type"]?.jsonPrimitive?.content ?: return null
    val entityId = obj["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    return when (type) {
        "state-icon", "icon" -> HaPictureElement.StateIcon(
            icon = HaIconMap.resolve(obj["icon"]?.jsonPrimitive?.content, entity),
            accent = HaStateColor.activeFor(entity),
            isActive = entity?.state == "on" || entity?.state == "open",
            tapAction = parseTap(obj, entityId),
        )
        "state-label" -> HaPictureElement.StateLabel(text = formatState(entity).rs)
        "service-button" -> {
            val title = obj["title"]?.jsonPrimitive?.content ?: "Action"
            HaPictureElement.ServiceButton(
                label = title.rs,
                accent = androidx.compose.ui.graphics.Color(0xFF1565C0),
                tapAction = parseTap(obj, entityId),
            )
        }
        else -> null
    }
}

private fun parseTap(obj: JsonObject, defaultEntity: String?): HaAction {
    val tapCfg = obj["tap_action"]?.jsonObject
    if (tapCfg != null) return parseHaAction(tapCfg, defaultEntity)
    return defaultTapActionFor(defaultEntity)
}
