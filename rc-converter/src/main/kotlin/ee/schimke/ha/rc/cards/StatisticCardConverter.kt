package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaStatisticCardData
import ee.schimke.ha.rc.components.RemoteHaStatisticCard
import kotlinx.serialization.json.jsonPrimitive

/**
 * `statistic` card — single statistic value (mean / min / max / sum)
 * for one entity. Reads `HaSnapshot.statistics[entityId]` and
 * aggregates the configured `stat_type` over the snapshot's window.
 * Falls back to the entity's current state when there's no statistic
 * data — keeps the card useful even before recorder backfill.
 */
class StatisticCardConverter : CardConverter {
    override val cardType: String = CardTypes.STATISTIC

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 110

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "Statistic"
        val unit = entity?.attributes?.get("unit_of_measurement")?.jsonPrimitive?.content
        val statType = card.raw["stat_type"]?.jsonPrimitive?.content ?: "mean"
        val period = card.raw["period"]?.jsonPrimitive?.content?.let { p ->
            "${p.replaceFirstChar { it.uppercaseChar() }} • ${statType.replaceFirstChar { it.uppercaseChar() }}"
        }

        val statistics = entityId?.let { snapshot.statistics[it].orEmpty() } ?: emptyList()
        val numericValue = if (statistics.isNotEmpty()) {
            val values = statistics.mapNotNull {
                when (statType) {
                    "min" -> it.min
                    "max" -> it.max
                    "sum" -> it.sum
                    "state" -> it.state
                    else -> it.mean
                }
            }
            when (statType) {
                "min" -> values.minOrNull()
                "max" -> values.maxOrNull()
                "sum" -> values.sum().takeIf { values.isNotEmpty() }
                else -> values.average().takeIf { values.isNotEmpty() }
            }
        } else null
        val valueLabel = numericValue?.let { formatValue(it) }
            ?: entity?.state?.takeUnless { it == "unknown" || it == "unavailable" }
            ?: "—"

        RemoteHaStatisticCard(
            HaStatisticCardData(
                name = name.rs,
                valueLabel = valueLabel.rs,
                unit = unit?.rs,
                periodLabel = period?.rs,
                accent = HaStateColor.activeFor(entity),
            ),
            modifier = modifier,
        )
    }
}

private fun formatValue(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d)
