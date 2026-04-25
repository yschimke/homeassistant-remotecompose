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
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.LocalHaTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * HA `history-graph` card — one row per entity summarising the history
 * available in the snapshot.
 *
 * RemoteCompose alpha08 doesn't yet expose a `RemoteCanvas` that lets us
 * paint per-point line segments at capture time without an explosion of
 * primitives, so this converter emits a textual summary
 * (`min – max (n samples)`) for each entity. When the history channel
 * stabilises we can replace the row body with a sparkline composable
 * without changing the converter / registration boundary.
 */
class HistoryGraphCardConverter : CardConverter {
    override val cardType: String = CardTypes.HISTORY_GRAPH

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val rows = entityIds(card).size.coerceAtLeast(1)
        val title = if (card.raw["title"] != null) 28 else 0
        return title + 24 + 32 * rows
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val theme = LocalHaTheme.current
        val title = card.raw["title"]?.jsonPrimitive?.content
        val hours = card.raw["hours_to_show"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24
        val ids = entityIds(card)

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
                RemoteText(
                    text = "Last ${hours}h".rs,
                    color = theme.secondaryText.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                )
                if (ids.isEmpty()) {
                    RemoteText(
                        text = "No entities".rs,
                        color = theme.secondaryText.rc,
                        fontSize = 13.rsp,
                        style = RemoteTextStyle.Default,
                    )
                } else {
                    ids.forEach { id -> EntityRow(id, snapshot) }
                }
            }
        }
    }

    @Composable
    private fun EntityRow(entityId: String, snapshot: HaSnapshot) {
        val theme = LocalHaTheme.current
        val name = snapshot.states[entityId]?.attributes?.get("friendly_name")
            ?.jsonPrimitive?.content
            ?: entityId
        val summary = summarise(snapshot.history[entityId].orEmpty())

        RemoteRow(
            modifier = RemoteModifier.fillMaxWidth(),
            verticalAlignment = RemoteAlignment.CenterVertically,
            horizontalArrangement = RemoteArrangement.SpaceBetween,
        ) {
            RemoteText(
                text = name.rs,
                color = theme.primaryText.rc,
                fontSize = 13.rsp,
                style = RemoteTextStyle.Default,
            )
            RemoteText(
                text = summary.rs,
                color = theme.secondaryText.rc,
                fontSize = 12.rsp,
                style = RemoteTextStyle.Default,
            )
        }
    }
}

private fun summarise(points: List<HistoryPoint>): String {
    if (points.isEmpty()) return "no data"
    val numeric = points.mapNotNull { it.state.toDoubleOrNull() }
    if (numeric.isEmpty()) return "${points.size} samples"
    val min = numeric.min()
    val max = numeric.max()
    val fmt: (Double) -> String = { d ->
        if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
    }
    return "${fmt(min)} – ${fmt(max)} (${points.size})"
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
