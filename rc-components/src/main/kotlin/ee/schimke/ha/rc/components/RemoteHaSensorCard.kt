@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable

/**
 * `sensor` card — entity name + big value + small inline sparkline of
 * the recent history. Implemented as a single-row reuse of the
 * history-graph composable so the visual stays consistent.
 */
@Composable
@RemoteComposable
fun RemoteHaSensorCard(
    data: HaSensorCardData,
    modifier: RemoteModifier = RemoteModifier,
) {
    RemoteHaHistoryGraph(
        HaHistoryGraphData(
            title = null,
            rangeLabel = data.rangeLabel ?: "".rs,
            rows = listOf(
                HaHistoryGraphRow(
                    name = data.name,
                    summary = data.valueLabel,
                    accent = data.accent,
                    points = data.points,
                ),
            ),
        ),
        modifier = modifier,
    )
}
