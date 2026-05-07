package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.components.HaSensorCardData
import ee.schimke.ha.rc.components.RemoteHaSensorCard
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.jsonPrimitive

/**
 * `sensor` card — entity name + big value + a small inline sparkline
 * of recent values. Pulls history from `HaSnapshot.history[entityId]`
 * and reuses the history-graph row visual as a hero tile.
 */
class SensorCardConverter : CardConverter {
    override val cardType: String = CardTypes.SENSOR

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 120

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val name = card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "Sensor"
        val hours = card.raw["hours_to_show"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24
        val history = entityId?.let { snapshot.history[it].orEmpty() } ?: emptyList()
        val numeric = history.mapNotNull { it.state.toFloatOrNull() }

        RemoteHaSensorCard(
            HaSensorCardData(
                name = name.rs,
                valueLabel = formatState(entity).rs,
                accent = HaStateColor.activeFor(entity),
                points = numeric,
                rangeLabel = "Last ${hours}h".rs,
            ),
            modifier = modifier,
        )
    }
}
