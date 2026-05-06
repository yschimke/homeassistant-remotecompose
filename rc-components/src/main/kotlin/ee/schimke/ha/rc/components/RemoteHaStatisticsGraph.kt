@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable

/**
 * `statistics-graph` card — same visual shape as the history-graph
 * sparkline (one row per entity, chrome + range label + small line
 * graph). Implemented as a thin shim over [RemoteHaHistoryGraph]; if
 * the visual diverges later (axes, multi-series fill, bar mode) split
 * into its own canvas pass.
 */
@Composable
@RemoteComposable
fun RemoteHaStatisticsGraph(
    data: HaStatisticsGraphData,
    modifier: RemoteModifier = RemoteModifier,
) {
    RemoteHaHistoryGraph(
        HaHistoryGraphData(
            title = data.title,
            rangeLabel = data.rangeLabel,
            rows = data.rows,
        ),
        modifier = modifier,
    )
}
