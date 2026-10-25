package ee.schimke.terrazzo.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.HaSession

/**
 * Cached dashboards-list state shared between dashboard management and the top-bar
 * `DashboardSwitcher`. Both surfaces want the same list — without hoisting we'd re-fetch from HA
 * each time the user opens the dropdown vs. visits the picker, which on a cold network is visible.
 */

/**
 * Load + cache the **unfiltered** dashboards list for the lifetime of [session]. A new [HaSession]
 * (sign-in / demo toggle / sign-out → sign-in) keys a fresh load. One resolution pass loads the
 * choices while this state is mounted; the list is not polled.
 *
 * Live sessions return supported Lovelace dashboards, including HA's default when renderable. An
 * empty list gets a single "Home" fallback (see below). The dashboard **selection**
 * (`PreferencesStore.selectedDashboardUrls`) is applied on top via
 * [rememberSelectedDashboardListState]; this raw version is what the selection screen renders so
 * the user can opt into / out of every dashboard the instance actually exposes.
 */
@Composable
fun rememberDashboardListState(session: HaSession): State<DashboardListState> {
  val cache = LocalTerrazzoGraph.current.offlineCache
  // Seed from disk synchronously so the first composition paints
  // already-known dashboards instead of the loading spinner. The
  // background fetch then upgrades the value to live data; failures
  // leave the cached list in place so an offline relaunch still
  // offers dashboard switching.
  val state =
    remember(session) {
      val seed =
        (session.dashboardsNow() ?: cache.dashboards(session.baseUrl))?.let {
          DashboardListState.Ready(it.ifNotEmptyOrHomeFallback())
        } ?: DashboardListState.Loading
      mutableStateOf<DashboardListState>(seed)
    }
  LaunchedEffect(session) {
    runCatching {
      session.connect()
      session.listDashboards()
    }
      .onSuccess { list -> state.value = DashboardListState.Ready(list.ifNotEmptyOrHomeFallback()) }
      .onFailure {
        // Only flip to Error if we have nothing to show. A cached
        // list is more useful than a blank error screen.
        if (state.value !is DashboardListState.Ready) {
          state.value = DashboardListState.Error(it.message ?: it::class.simpleName ?: "error")
        }
      }
  }
  return state
}

/**
 * Same as [rememberDashboardListState] but with the user's `selectedDashboardUrls` filter applied.
 * The top-bar switcher consumes this so optional dashboard management can limit its choices.
 *
 * Filtering rules:
 * - selection `null` — no saved filter: offer every supported dashboard.
 * - selection non-empty — keep only entries whose [DashboardSummary.selectionKey] is in the set. If
 *   everything got filtered out (the user removed a dashboard from HA since choosing), fall back to
 *   the raw list so they at least see *something* and can re-pick from Settings.
 * - selection empty — should not occur (the selection screen disables the Done button on empty),
 *   but if it does we fall back to the raw list rather than render a blank picker.
 */
@Composable
fun rememberSelectedDashboardListState(session: HaSession): State<DashboardListState> {
  val rawState = rememberDashboardListState(session)
  val selectionState =
    LocalTerrazzoGraph.current.preferencesStore.selectedDashboardUrls.collectAsState(initial = null)
  return remember(rawState, selectionState) {
    derivedStateOf { applyDashboardSelection(rawState.value, selectionState.value) }
  }
}

internal fun applyDashboardSelection(
  raw: DashboardListState,
  selection: Set<String>?,
): DashboardListState {
  if (raw !is DashboardListState.Ready || selection.isNullOrEmpty()) return raw
  val filtered =
    raw.dashboards.filter { d ->
      val key = d.urlPath ?: PreferencesStore.DEFAULT_DASHBOARD_SENTINEL
      key in selection
    }
  // Empty filter result: user's selection no longer matches anything
  // the instance exposes (dashboards renamed / removed). Show the
  // raw list so they're not stuck staring at a blank picker — they
  // can re-run selection from Settings.
  return if (filtered.isEmpty()) raw else DashboardListState.Ready(filtered)
}

private fun List<DashboardSummary>.ifNotEmptyOrHomeFallback(): List<DashboardSummary> = ifEmpty {
  listOf(DashboardSummary(urlPath = null, title = "Home"))
}
