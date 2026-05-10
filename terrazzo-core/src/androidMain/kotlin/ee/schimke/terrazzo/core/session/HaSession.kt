package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

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
    val connectionStatus: StateFlow<SessionConnectionStatus>

    /**
     * If non-null, dashboard screens should re-fetch snapshots at this
     * cadence so values visibly change on screen. Live sessions return
     * null until we wire real subscriptions.
     */
    val refreshIntervalMillis: Long? get() = null

    suspend fun connect()
    suspend fun listDashboards(): List<DashboardSummary>
    suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot>

    /**
     * Fire-and-await an HA service call. The dashboard's
     * `HaActionDispatcher` invokes this when a Lovelace tap action
     * decodes to `CallService` / `Toggle`. Default does nothing so
     * sessions that can't talk to HA (demo, tests) opt out cleanly.
     */
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String? = null,
        serviceData: JsonObject = JsonObject(emptyMap()),
    ): Unit = Unit

    suspend fun close()
}

enum class SessionConnectionStatus {
    Failed,
    Connecting,
    Connected,
}

/** Live session backed by an HA WebSocket. */
class LiveHaSession(
    override val baseUrl: String,
    accessToken: String,
) : HaSession {
    private val client = HaClient(HaConfig(baseUrl = baseUrl, accessToken = accessToken))
    private val _connectionStatus = MutableStateFlow(SessionConnectionStatus.Connecting)
    override val connectionStatus: StateFlow<SessionConnectionStatus> = _connectionStatus

    override suspend fun connect() {
        _connectionStatus.value = SessionConnectionStatus.Connecting
        runCatching { client.connect() }
            .onSuccess { _connectionStatus.value = SessionConnectionStatus.Connected }
            .onFailure { _connectionStatus.value = SessionConnectionStatus.Failed; throw it }
    }
    override suspend fun listDashboards(): List<DashboardSummary> = client.listDashboards()
    override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> {
        val dashboard = client.fetchDashboard(urlPath)
        val snapshot = client.snapshot()
        return dashboard to snapshot
    }
    override suspend fun callService(
        domain: String,
        service: String,
        entityId: String?,
        serviceData: JsonObject,
    ) = client.callService(domain, service, entityId, serviceData)
    override suspend fun close() {
        client.close()
        _connectionStatus.value = SessionConnectionStatus.Failed
    }
}
