package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.DashboardSummary

/** Open the remembered dashboard, HA's default, or the first available supported dashboard. */
fun preferredDashboardPath(dashboards: List<DashboardSummary>, remembered: String?): String? {
  val preferred = remembered?.takeUnless { it == DEFAULT_DASHBOARD_SENTINEL }
  if (dashboards.isEmpty() || dashboards.any { it.urlPath == preferred }) return preferred
  return if (dashboards.any { it.urlPath == null }) null
  else dashboards.firstOrNull { it.urlPath == "lovelace" }?.urlPath ?: dashboards.first().urlPath
}
