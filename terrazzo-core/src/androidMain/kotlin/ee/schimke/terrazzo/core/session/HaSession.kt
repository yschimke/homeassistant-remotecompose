package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.HaInstanceConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaNotification
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
     * HA long-lived bearer token, if this session has one. Live sessions
     * mint one at login; demo / offline sessions return null. Surfaced
     * here so the image stack can build an `ImageLoader` that adds
     * `Authorization: Bearer ...` to `image_proxy` / addon-icon fetches
     * (paths that don't carry HA's `?token=` signed-path token).
     */
    val accessToken: String? get() = null

    /**
     * If non-null, dashboard screens should re-fetch snapshots at this
     * cadence so values visibly change on screen. Live sessions return
     * null until we wire real subscriptions.
     */
    val refreshIntervalMillis: Long? get() = null

    /**
     * Current set of HA *persistent notifications* — the bell-icon list
     * in HA's frontend. Updated reactively as HA fires
     * `persistent_notifications_updated`. Sessions that can't observe
     * HA (demo, offline) hold an empty list forever.
     *
     * Note: this is NOT mobile-app push. HA delivers those via FCM /
     * APNS and they don't appear on the WebSocket. This is what shows
     * up when an automation calls `persistent_notification.create` or
     * an integration raises a discovery banner.
     */
    val notifications: StateFlow<List<HaNotification>>
        get() = EMPTY_NOTIFICATIONS

    companion object {
        private val EMPTY_NOTIFICATIONS: StateFlow<List<HaNotification>> =
            MutableStateFlow(emptyList())
    }

    suspend fun connect()
    suspend fun listDashboards(): List<DashboardSummary>
    suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot>

    /**
     * Fetch HA's `get_config` payload — version + `internal_url` / `external_url`. Live sessions
     * round-trip the WebSocket; demo / offline sessions return null so callers can skip the
     * persist-public-URL side effect.
     */
    suspend fun fetchInstanceConfig(): HaInstanceConfig? = null

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

    /**
     * Dismiss a single persistent notification by id — removes it from
     * HA's bell-icon list. Live sessions round-trip
     * `persistent_notification.dismiss`; HA then re-broadcasts the
     * shortened list over the subscription. Sessions that can't observe
     * notifications (demo, offline) no-op.
     */
    suspend fun dismissNotification(notificationId: String): Unit = Unit

    /**
     * Clear every persistent notification at once
     * (`persistent_notification.dismiss_all`). Default no-op for sessions
     * with nothing to clear.
     */
    suspend fun dismissAllNotifications(): Unit = Unit

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
    override val accessToken: String,
    engine: HttpClientEngine? = null,
) : HaSession {
    private val client =
        HaClient(HaConfig(baseUrl = baseUrl, accessToken = accessToken), engine = engine)
    private val _connectionStatus = MutableStateFlow(SessionConnectionStatus.Connecting)
    override val connectionStatus: StateFlow<SessionConnectionStatus> = _connectionStatus
    private val _notifications = MutableStateFlow<List<HaNotification>>(emptyList())
    override val notifications: StateFlow<List<HaNotification>> = _notifications.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Bridge the underlying socket's state into our session status so
        // a mid-flight disconnect (read loop catches a throwable, peer
        // hangs up) flips us to Failed. Without this, the app's
        // RESUMED-state reconnect loop never fires after the WebSocket
        // dies under a long-lived session — the next dashboard switch
        // sees the dead session and surfaces "Software caused connection
        // abort" with no recovery path.
        client.state
            .onEach { state ->
                when (state) {
                    HaClient.ConnectionState.Ready ->
                        _connectionStatus.value = SessionConnectionStatus.Connected
                    HaClient.ConnectionState.Connecting,
                    HaClient.ConnectionState.Authenticating ->
                        _connectionStatus.value = SessionConnectionStatus.Connecting
                    HaClient.ConnectionState.Disconnected,
                    HaClient.ConnectionState.Error ->
                        _connectionStatus.value = SessionConnectionStatus.Failed
                }
            }
            .launchIn(scope)

        // Bootstrap the persistent-notification stream on every Ready
        // transition. `collectLatest` cancels the previous body when
        // state changes, so a Disconnected → Ready cycle re-seeds and
        // re-subscribes cleanly without doubling up collectors. The
        // previously seen list is kept on the floor in between so the
        // UI doesn't blink to empty during a brief reconnect.
        scope.launch {
            client.state.collectLatest { state ->
                if (state != HaClient.ConnectionState.Ready) return@collectLatest
                runCatching { _notifications.value = client.fetchPersistentNotifications() }
                runCatching { client.subscribeEvents("persistent_notifications_updated") }
                    .getOrNull()
                    ?.collect {
                        // The event payload is empty — HA only signals
                        // "something changed". Refetch to learn what.
                        runCatching {
                            _notifications.value = client.fetchPersistentNotifications()
                        }
                    }
            }
        }
    }

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
    override suspend fun fetchInstanceConfig(): HaInstanceConfig = client.fetchConfig()
    override suspend fun callService(
        domain: String,
        service: String,
        entityId: String?,
        serviceData: JsonObject,
    ) = client.callService(domain, service, entityId, serviceData)
    override suspend fun dismissNotification(notificationId: String) {
        // Optimistically drop it so the bell/sheet update on the tap
        // without waiting for HA's round-trip; the
        // `persistent_notifications_updated` refetch then confirms the
        // authoritative list (and restores it if the dismiss failed).
        _notifications.value = _notifications.value.filterNot { it.notificationId == notificationId }
        client.dismissNotification(notificationId)
    }
    override suspend fun dismissAllNotifications() {
        _notifications.value = emptyList()
        client.dismissAllNotifications()
    }
    override suspend fun close() {
        client.close()
        scope.cancel()
        _connectionStatus.value = SessionConnectionStatus.Failed
    }
}
