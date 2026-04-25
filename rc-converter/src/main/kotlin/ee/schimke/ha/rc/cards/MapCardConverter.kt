@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.LocalHaTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA `map` card — list of entities with optional locations.
 *
 * The "real" map card uses Leaflet to render a slippy-map background.
 * We can't ship tiles in a `.rc` document, so this converter renders
 * a flat card chrome with each tracked entity as a labelled dot. It
 * preserves the slot the dashboard expects without faking map tiles.
 *
 * Reads `entities:` (string or `{ entity }`); falls back to the title
 * heuristic from other cards.
 */
class MapCardConverter : CardConverter {
    override val cardType: String = CardTypes.MAP

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val entities = entityIds(card)
        val title = if (card.raw["title"] != null) 28 else 0
        return title + 24 + 28 * entities.size.coerceAtLeast(1)
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val theme = LocalHaTheme.current
        val title = card.raw["title"]?.jsonPrimitive?.content
        val entities = entityIds(card)

        RemoteBox(
            modifier = modifier
                .fillMaxWidth()
                .clip(RemoteRoundedCornerShape(12.rdp))
                .background(theme.cardBackground.rc)
                .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
                .padding(horizontal = 12.rdp, vertical = 10.rdp),
        ) {
            RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(6.rdp)) {
                if (title != null) {
                    RemoteText(
                        text = title.rs,
                        color = theme.primaryText.rc,
                        fontSize = 15.rsp,
                        fontWeight = FontWeight.Medium,
                        style = RemoteTextStyle.Default,
                    )
                }
                if (entities.isEmpty()) {
                    RemoteText(
                        text = "No entities".rs,
                        color = theme.secondaryText.rc,
                        fontSize = 13.rsp,
                        style = RemoteTextStyle.Default,
                    )
                } else {
                    entities.forEach { id -> EntityRow(id, snapshot) }
                }
            }
        }
    }

    @Composable
    private fun EntityRow(entityId: String, snapshot: HaSnapshot) {
        val theme = LocalHaTheme.current
        val state = snapshot.states[entityId]
        val name = state?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: entityId
        val lat = state?.attributes?.get("latitude")?.jsonPrimitive?.content
        val lon = state?.attributes?.get("longitude")?.jsonPrimitive?.content
        val coords = if (lat != null && lon != null) "$lat, $lon" else (state?.state ?: "—")

        RemoteRow(
            verticalAlignment = RemoteAlignment.CenterVertically,
            horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
        ) {
            RemoteBox(
                modifier = RemoteModifier
                    .size(8.rdp)
                    .clip(RemoteCircleShape)
                    .background(theme.placeholderAccent.rc),
            )
            RemoteText(
                text = name.rs,
                color = theme.primaryText.rc,
                fontSize = 13.rsp,
                style = RemoteTextStyle.Default,
            )
            RemoteText(
                text = coords.rs,
                color = theme.secondaryText.rc,
                fontSize = 12.rsp,
                style = RemoteTextStyle.Default,
            )
        }
    }
}

private fun entityIds(card: CardConfig): List<String> {
    val arr: JsonArray = card.raw["entities"]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> el.content
            is JsonObject -> el["entity"]?.jsonPrimitive?.content
            else -> null
        }
    }
}
