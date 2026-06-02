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
 * Cached dashboards-list state shared between [DashboardPickerScreen] (the first-launch experience)
 * and the top-bar `DashboardSwitcher` (the in-flight quick-switcher). Both surfaces want the same
 * list — without hoisting we'd re-fetch from HA each time the user opens the dropdown vs. visits
 * the picker, which on a cold network is visible.
 */
sealed interface DashboardListState {
  data object Loading : DashboardListState

  data class Error(val message: String) : DashboardListState

  /**
   * Dashboards as returned by HA, with one synthetic exception: HA returns an *empty* list when the
   * only dashboard is the default one, so we materialise a `(urlPath = null, title = "Home")` entry
   * in that case so the picker / switcher always have something to render.
   */
  data class Ready(val dashboards: List<DashboardSummary>) : DashboardListState
}

/**
 * Load + cache the **unfiltered** dashboards list for the lifetime of [session]. A new [HaSession]
 * (sign-in / demo toggle / sign-out → sign-in) keys a fresh load. Within one session this is one
 * HTTP-equivalent call, and dashboards are stable enough on the HA side that we don't poll.
 *
 * The result is exactly what HA returned (plus a single "Home" fallback when HA returned nothing —
 * see below). The dashboard **selection** (`PreferencesStore.selectedDashboardUrls`) is applied on
 * top via [rememberSelectedDashboardListState]; this raw version is what the selection screen
 * renders so the user can opt into / out of every dashboard the instance actually exposes.
 */
@Composable
fun rememberDashboardListState(session: HaSession): State<DashboardListState> {
  val cache = LocalTerrazzoGraph.current.offlineCache
  // Seed from disk synchronously so the first composition paints
  // already-known dashboards instead of the loading spinner. The
  // background fetch then upgrades the value to live data; failures
  // leave the cached list in place so an offline relaunch still
  // shows the picker.
  val state =
    remember(session) {
      val seed =
        cache.dashboards(session.baseUrl)?.let {
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
 * The picker and the top-bar switcher both consume this so the user only ever sees the dashboards
 * they opted into during the signin flow / Settings.
 *
 * Filtering rules:
 * - selection `null` — user hasn't been through the selection screen yet (sign-in flow gates on
 *   this). Show the raw list unfiltered so the brief window before the selection screen mounts
 *   doesn't blank.
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
    derivedStateOf { applySelection(rawState.value, selectionState.value) }
  }
}

private fun applySelection(raw: DashboardListState, selection: Set<String>?): DashboardListState {
  if (raw !is DashboardListState.Ready || selection.isNullOrEmpty()) return raw
  val filtered =
    raw.dashboards.filter { d ->
      val key = d.urlPath ?: PreferencesStore.DEFAULT_DASHBOARD_SENTINEL
      key in selection
    }
  // If selection includes the built-in default but HA's response
  // didn't (it never does — HA hides the unnamed dashboard from
  // `lovelace/dashboards/list`), stitch it in so the user can open
  // what they picked.
  val withDefault =
    if (
      PreferencesStore.DEFAULT_DASHBOARD_SENTINEL in selection &&
        filtered.none { it.urlPath == null }
    ) {
      listOf(builtInDefaultDashboard) + filtered
    } else {
      filtered
    }
  // Empty filter result: user's selection no longer matches anything
  // the instance exposes (dashboards renamed / removed). Show the
  // raw list so they're not stuck staring at a blank picker — they
  // can re-run selection from Settings.
  return if (withDefault.isEmpty()) raw else DashboardListState.Ready(withDefault)
}

private fun List<DashboardSummary>.ifNotEmptyOrHomeFallback(): List<DashboardSummary> = ifEmpty {
  listOf(DashboardSummary(urlPath = null, title = "Home"))
}
