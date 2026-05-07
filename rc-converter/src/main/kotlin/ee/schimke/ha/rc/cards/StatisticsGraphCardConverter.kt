package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * `statistics-graph` card — mirrors history-graph but reads
 * pre-aggregated values from `HaSnapshot.statistics`.
 *
 * Supports HA's `stat_types` fan-out: each declared stat (`mean`, `min`,
 * `max`, `sum`, `state`) becomes its own row per entity, picking the
 * matching field on [StatisticPoint]. `chart_type: bar` and the
 * `stack: true` modifier are forwarded to the renderer; absent
 * `stat_types` defaults to `[mean]`.
 */
class StatisticsGraphCardConverter : CardConverter {
    override val cardType: String = CardTypes.STATISTICS_GRAPH

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val rows = (entityIds(card).size * statTypes(card).size).coerceAtLeast(1)
        val title = if (card.raw["title"] != null) 24 else 0
        return title + 16 + 52 * rows + 20
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val period = card.raw["period"]?.jsonPrimitive?.content ?: "hour"
        val ids = entityIds(card)
        val stats = statTypes(card)
        val chartType = card.raw["chart_type"]?.jsonPrimitive?.content ?: "line"
        val stacked = card.raw["stack"]?.jsonPrimitive?.booleanOrNull == true && chartType == "bar"

        val rows =
            ids.flatMap { id ->
                val entity = snapshot.states[id]
                val baseName =
                    entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id
                val statistics = snapshot.statistics[id].orEmpty()
                stats.map { statType ->
                    val numeric = statistics.mapNotNull { selectStat(it, statType)?.toFloat() }
                    val rowName = if (stats.size > 1) "$baseName · $statType" else baseName
                    HaHistoryGraphRow(
                        entityId = id,
                        name = rowName,
                        summary = summariseStatistics(statistics, statType),
                        accent = HaStateColor.activeFor(entity),
                        points = numeric,
                    )
                }
            }

        RemoteHaStatisticsGraph(
            HaStatisticsGraphData(
                title = title,
                rangeLabel = "Period: $period",
                rows = rows,
                chartType = chartType,
                stacked = stacked,
            ),
            modifier = modifier,
        )
    }
}

private fun selectStat(point: StatisticPoint, statType: String): Double? = when (statType) {
    "mean" -> point.mean
    "min" -> point.min
    "max" -> point.max
    "sum" -> point.sum
    "state" -> point.state
    else -> point.mean
}

private fun summariseStatistics(points: List<StatisticPoint>, statType: String): String {
    if (points.isEmpty()) return "no data"
    val numeric = points.mapNotNull { selectStat(it, statType) }
    if (numeric.isEmpty()) return "${points.size} buckets"
    val min = numeric.min()
    val max = numeric.max()
    val fmt: (Double) -> String = { d ->
        if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
    }
    return "${fmt(min)} – ${fmt(max)} (${points.size})"
}

private fun statTypes(card: CardConfig): List<String> {
    val arr = card.raw["stat_types"]?.jsonArray
    val parsed = arr?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    return parsed.ifEmpty { listOf("mean") }
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
