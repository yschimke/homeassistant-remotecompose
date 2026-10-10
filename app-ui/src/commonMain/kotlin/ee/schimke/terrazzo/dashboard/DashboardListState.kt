package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.DashboardSummary

const val DEFAULT_DASHBOARD_SENTINEL: String = "__default__"

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
