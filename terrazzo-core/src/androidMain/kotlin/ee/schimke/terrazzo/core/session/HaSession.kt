package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot

/**
 * One HA session held for the app's lifetime. A facade over whatever
 * produces dashboards and entity state — a live HA WebSocket (see
 * [LiveHaSession]) or a deterministic fake (see [DemoHaSession]).
 *
 * Not a graph binding — sessions are constructed per-login (or per
 * demo-enable) via [HaSessionFactory].
 */
interface HaSession {
    val baseUrl: String

    /**
     * If non-null, dashboard screens should re-fetch snapshots at this
     * cadence so values visibly change on screen. Live sessions return
     * null until we wire real subscriptions.
     */
    val refreshIntervalMillis: Long? get() = null

    suspend fun connect()
    suspend fun listDashboards(): List<DashboardSummary>
    suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot>
    suspend fun close()
}

/** Live session backed by an HA WebSocket. */
class LiveHaSession(
    override val baseUrl: String,
    accessToken: String,
) : HaSession {
    private val client = HaClient(HaConfig(baseUrl = baseUrl, accessToken = accessToken))

    override suspend fun connect() = client.connect()
    override suspend fun listDashboards(): List<DashboardSummary> = client.listDashboards()
    override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> {
        val dashboard = client.fetchDashboard(urlPath)
        val snapshot = client.snapshot()
        return dashboard to snapshot
    }
    override suspend fun close() = client.close()
}
