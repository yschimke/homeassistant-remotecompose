package ee.schimke.terrazzo.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ee.schimke.ha.client.DashboardSummary
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
    val state = remember(session) { mutableStateOf<DashboardListState>(DashboardListState.Loading) }
    LaunchedEffect(session) {
        runCatching {
            session.connect()
            session.listDashboards()
        }.onSuccess { list ->
            state.value = DashboardListState.Ready(
                dashboards = list.ifEmpty {
                    listOf(DashboardSummary(urlPath = null, title = "Home"))
                },
            )
        }.onFailure {
            state.value = DashboardListState.Error(it.message ?: it::class.simpleName ?: "error")
        }
    }
    return state
}
