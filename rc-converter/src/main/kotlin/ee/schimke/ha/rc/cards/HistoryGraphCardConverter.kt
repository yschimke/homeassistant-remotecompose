package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaHistoryGraphData
import ee.schimke.ha.rc.components.HaHistoryGraphRow
import ee.schimke.ha.rc.components.RemoteHaHistoryGraph
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `history-graph` card. Renders one sparkline per entity using the
 * snapshot's `history[entityId]`. The per-row summary text is a named
 * `<entity>.state` binding so the host can refresh it without a
 * re-encode. Numeric points are normalised into the row's drawable rect
 * at capture time; alpha010 still doesn't expose a RemoteFloat list
 * binding, so the sparkline geometry itself is re-encoded when new
 * samples arrive.
 */
class HistoryGraphCardConverter : CardConverter {
    override val cardType: String = CardTypes.HISTORY_GRAPH

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val rows = entityIds(card).size.coerceAtLeast(1)
        val title = if (card.raw["title"] != null) 24 else 0
        // ~52 dp per row (label + sparkline + spacing) + range label + padding.
        return title + 16 + 52 * rows + 20
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val hours = card.raw["hours_to_show"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24
        val ids = entityIds(card)

        val rows =
            ids.map { id ->
                val entity = snapshot.states[id]
                val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id
                val history = snapshot.history[id].orEmpty()
                val numeric = history.mapNotNull { it.state.toFloatOrNull() }
                HaHistoryGraphRow(
                    entityId = id,
                    name = name,
                    summary = summarise(history),
                    accent = HaStateColor.activeFor(entity),
                    points = numeric,
                )
            }

        RemoteHaHistoryGraph(
            HaHistoryGraphData(title = title, rangeLabel = "Last ${hours}h", rows = rows),
            modifier = modifier,
        )
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
