package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot

/**
 * One connected HA session held for the app's lifetime. A thin facade
 * over [HaClient] so callers (the dashboard screens and the widget
 * provider) don't each have to manage the WebSocket connection.
 */
class HaSession(
    val baseUrl: String,
    accessToken: String,
) {
    private val client = HaClient(HaConfig(baseUrl = baseUrl, accessToken = accessToken))

    suspend fun connect() = client.connect()

    suspend fun listDashboards(): List<DashboardSummary> = client.listDashboards()

    suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> {
        val dashboard = client.fetchDashboard(urlPath)
        val snapshot = client.snapshot()
        return dashboard to snapshot
    }

    suspend fun close() = client.close()
}
