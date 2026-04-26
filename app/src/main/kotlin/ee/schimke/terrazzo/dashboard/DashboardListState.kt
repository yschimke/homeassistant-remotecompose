package ee.schimke.terrazzo.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.session.HaSession

/**
 * Cached dashboards-list state shared between [DashboardPickerScreen]
 * (the first-launch experience) and the top-bar
 * `DashboardSwitcher` (the in-flight quick-switcher). Both surfaces
 * want the same list — without hoisting we'd re-fetch from HA each
 * time the user opens the dropdown vs. visits the picker, which on a
 * cold network is visible.
 */
sealed interface DashboardListState {
    data object Loading : DashboardListState
    data class Error(val message: String) : DashboardListState

    /**
     * Dashboards as returned by HA, with one synthetic exception: HA
     * returns an *empty* list when the only dashboard is the default
     * one, so we materialise a `(urlPath = null, title = "Home")` entry
     * in that case so the picker / switcher always have something to
     * render.
     */
    data class Ready(val dashboards: List<DashboardSummary>) : DashboardListState
}

/**
 * Load + cache the dashboards list for the lifetime of [session]. A
 * new [HaSession] (sign-in / demo toggle / sign-out → sign-in) keys a
 * fresh load. Within one session this is one HTTP-equivalent call,
 * and dashboards are stable enough on the HA side that we don't poll.
 */
@Composable
fun rememberDashboardListState(session: HaSession): State<DashboardListState> {
    val cache = LocalTerrazzoGraph.current.offlineCache
    // Seed from disk synchronously so the first composition paints
    // already-known dashboards instead of the loading spinner. The
    // background fetch then upgrades the value to live data; failures
    // leave the cached list in place so an offline relaunch still
    // shows the picker.
    val state = remember(session) {
        val seed = cache.dashboards(session.baseUrl)
            ?.let { DashboardListState.Ready(it.ifNotEmptyOrHomeFallback()) }
            ?: DashboardListState.Loading
        mutableStateOf<DashboardListState>(seed)
    }
    LaunchedEffect(session) {
        runCatching {
            session.connect()
            session.listDashboards()
        }.onSuccess { list ->
            state.value = DashboardListState.Ready(list.ifNotEmptyOrHomeFallback())
        }.onFailure {
            // Only flip to Error if we have nothing to show. A cached
            // list is more useful than a blank error screen.
            if (state.value !is DashboardListState.Ready) {
                state.value =
                    DashboardListState.Error(it.message ?: it::class.simpleName ?: "error")
            }
        }
    }
    return state
}

private fun List<DashboardSummary>.ifNotEmptyOrHomeFallback(): List<DashboardSummary> =
    ifEmpty { listOf(DashboardSummary(urlPath = null, title = "Home")) }
