package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.DashboardSummary

const val DEFAULT_DASHBOARD_SENTINEL: String = "__default__"

sealed interface DashboardListState {
  data object Loading : DashboardListState

  data class Error(val message: String) : DashboardListState

  /** Dashboard choices supplied by the host; the shared shell validates Lovelace views. */
  data class Ready(val dashboards: List<DashboardSummary>) : DashboardListState
}
