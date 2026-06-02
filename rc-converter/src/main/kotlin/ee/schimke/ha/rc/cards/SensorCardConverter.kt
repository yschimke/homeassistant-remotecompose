@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaSensorCardData
import ee.schimke.ha.rc.components.HaTileData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaIconChip
import ee.schimke.ha.rc.components.RemoteHaSensorCard
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import kotlinx.serialization.json.jsonPrimitive

/**
 * `sensor` card — entity name + big value + a small inline sparkline
 * of recent values. Pulls history from `HaSnapshot.history[entityId]`
 * and reuses the history-graph row visual as a hero tile.
 *
 * In [CardSizeMode.Fixed] the converter runs the shared icon-+-state
 * ladder (adaptive-card-layouts.md §"Icon + state row/tile"): a narrow
 * cell drops to the [RemoteHaIconChip] identity tier (tinted icon +
 * value, sparkline + name dropped — the sparkline is the card's P5, so
 * it's the first thing to go), while the full [RemoteHaSensorCard]
 * promotes the sparkline back on cells with the width to host it, and is
 * [CenteredCell]-wrapped so a tall cell fills rather than top-glues.
 */
class SensorCardConverter : CardConverter {
    override val cardType: String = CardTypes.SENSOR

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 120

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        when (LocalCardSizeMode.current) {
            CardSizeMode.Wrap -> FullSensor(card, snapshot, modifier)
            CardSizeMode.Fixed ->
                RemoteSizeBreakpoint(
                    thresholdsDp = intArrayOf(120),
                    modifier = modifier.fillMaxSize(),
                ) { tier ->
                    when (tier) {
                        0 -> RemoteHaIconChip(iconChipData(card, snapshot), RemoteModifier.fillMaxSize())
                        else -> CenteredCell { FullSensor(card, snapshot, RemoteModifier.fillMaxWidth()) }
                    }
                }
        }
    }

    @Composable
    private fun FullSensor(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val hours = card.raw["hours_to_show"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24
        val history = entityId?.let { snapshot.history[it].orEmpty() } ?: emptyList()
        val numeric = history.mapNotNull { it.state.toFloatOrNull() }

        RemoteHaSensorCard(
            HaSensorCardData(
                entityId = entityId,
                name = nameFor(card, snapshot),
                valueLabel = formatState(entity),
                accent = HaStateColor.activeFor(entity),
                points = numeric,
                rangeLabel = "Last ${hours}h",
            ),
            modifier = modifier,
        )
    }

    /** Identity-tier payload shaped as [HaTileData] so the shared
     *  [RemoteHaIconChip] can render the icon + live value. */
    @Composable
    private fun iconChipData(card: CardConfig, snapshot: HaSnapshot): HaTileData {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        val accent = HaStateColor.activeFor(entity).rc
        return HaTileData(
            entityId = entityId,
            name = nameFor(card, snapshot),
            state = LiveValues.state(entityId, formatState(entity)),
            icon = HaIconMap.resolve(card.raw["icon"]?.jsonPrimitive?.content, entity),
            accent =
                HaToggleAccent(
                    activeAccent = accent,
                    inactiveAccent = accent,
                    toggleable = false,
                ),
        )
    }

    private fun nameFor(card: CardConfig, snapshot: HaSnapshot): String {
        val entityId = card.raw["entity"]?.jsonPrimitive?.content
        val entity = entityId?.let { snapshot.states[it] }
        return card.raw["name"]?.jsonPrimitive?.content
            ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
            ?: entityId
            ?: "Sensor"
    }
}
