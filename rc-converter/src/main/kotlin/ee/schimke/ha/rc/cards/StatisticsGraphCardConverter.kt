package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.StatisticPoint
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaHistoryGraphRow
import ee.schimke.ha.rc.components.HaStatisticsGraphData
import ee.schimke.ha.rc.components.RemoteHaStatisticsGraph
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `statistics-graph` card — same visual as history-graph but reads
 * pre-aggregated `mean` values from `HaSnapshot.statistics`. HA's
 * `chart_type: bar` and `stat_types` fan-out aren't visualised yet
 * (would need a per-bucket rect pass); the textual summary still
 * conveys range and sample count.
 */
class StatisticsGraphCardConverter : CardConverter {
    override val cardType: String = CardTypes.STATISTICS_GRAPH

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val rows = entityIds(card).size.coerceAtLeast(1)
        val title = if (card.raw["title"] != null) 24 else 0
        return title + 16 + 52 * rows + 20
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val period = card.raw["period"]?.jsonPrimitive?.content ?: "hour"
        val ids = entityIds(card)
        val chartType = card.raw["chart_type"]?.jsonPrimitive?.content ?: "line"

        val rows = ids.map { id ->
            val entity = snapshot.states[id]
            val name = entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id
            val statistics = snapshot.statistics[id].orEmpty()
            val numeric = statistics.mapNotNull { it.mean?.toFloat() ?: it.state?.toFloat() }
            HaHistoryGraphRow(
                name = name.rs,
                summary = summariseStatistics(statistics).rs,
                accent = HaStateColor.activeFor(entity),
                points = numeric,
            )
        }

        RemoteHaStatisticsGraph(
            HaStatisticsGraphData(
                title = title?.rs,
                rangeLabel = "Period: $period".rs,
                rows = rows,
                chartType = chartType,
            ),
            modifier = modifier,
        )
    }
}

private fun summariseStatistics(points: List<StatisticPoint>): String {
    if (points.isEmpty()) return "no data"
    val numeric = points.mapNotNull { it.mean ?: it.state }
    if (numeric.isEmpty()) return "${points.size} buckets"
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
