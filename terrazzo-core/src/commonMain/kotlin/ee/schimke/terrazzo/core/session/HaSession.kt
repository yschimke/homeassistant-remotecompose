package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaAuthInvalidException
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.HaInstanceConfig
import ee.schimke.ha.client.stateSnapshots
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaNotification
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import io.ktor.client.engine.HttpClientEngine
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * One HA session held for the app's lifetime. A facade over whatever produces dashboards and entity
 * state — a live HA WebSocket (see [LiveHaSession]) or a deterministic fake (see [DemoHaSession]).
 *
 * Not a graph binding — sessions are constructed per-login (or per demo-enable) via
 * [HaSessionFactory].
 */
interface HaSession {
  val baseUrl: String
  val connectionStatus: StateFlow<SessionConnectionStatus>

  /**
   * HA long-lived bearer token, if this session has one. Live sessions mint one at login; demo /
   * offline sessions return null. Surfaced here so the image stack can build an `ImageLoader` that
   * adds `Authorization: Bearer ...` to `image_proxy` / addon-icon fetches (paths that don't carry
   * HA's `?token=` signed-path token).
   */
  val accessToken: String?
    get() = null

  /**
   * If non-null, dashboard screens should re-fetch snapshots at this cadence so values visibly
   * change on screen. Live sessions return null: they push changes through [liveStates] instead.
   */
  val refreshIntervalMillis: Long?
    get() = null

  /**
   * Every entity's current state, kept current by Home Assistant's `state_changed` events while the
   * session is connected: fetched once per connection, then patched per event, and re-armed on
   * every reconnect. Null while there's no live feed — not connected yet, between reconnects, or a
   * session that can't observe HA (demo, offline). Dashboards follow it with
   * [liveDashboardSnapshots].
   */
  val liveStates: StateFlow<HaSnapshot?>
    get() = NO_LIVE_STATES

  /**
   * True once Home Assistant refused this session's stored credentials (the refresh token was
   * revoked or the user deactivated). Reconnecting can't recover; the app signs the user out so
   * they can sign in again. Sessions without credentials stay false.
   */
  val needsReauth: StateFlow<Boolean>
    get() = NEVER_NEEDS_REAUTH

  /**
   * Current set of HA *persistent notifications* — the bell-icon list in HA's frontend. Updated
   * reactively as HA fires `persistent_notifications_updated`. Sessions that can't observe HA
   * (demo, offline) hold an empty list forever.
   *
   * Note: this is NOT mobile-app push. HA delivers those via FCM / APNS and they don't appear on
   * the WebSocket. This is what shows up when an automation calls `persistent_notification.create`
   * or an integration raises a discovery banner.
   */
  val notifications: StateFlow<List<HaNotification>>
    get() = EMPTY_NOTIFICATIONS

  companion object {
    private val EMPTY_NOTIFICATIONS: StateFlow<List<HaNotification>> = MutableStateFlow(emptyList())
    private val NO_LIVE_STATES: StateFlow<HaSnapshot?> = MutableStateFlow(null)
    private val NEVER_NEEDS_REAUTH: StateFlow<Boolean> = MutableStateFlow(false)
  }

  suspend fun connect()

  /**
   * Gracefully park the live connection without tearing the session down (unlike [close], which
   * makes it unusable). Sessions holding a socket close it with a clean reason and move to
   * [SessionConnectionStatus.Disconnected]; sessions with nothing to disconnect (demo, offline)
   * no-op. Pair with [connect] to resume.
   *
   * Driven by the app-lifecycle hook: when the app backgrounds we drop the WebSocket here so the OS
   * tearing it down later doesn't surface as a spurious Error, and reopen it via [connect] on
   * return.
   */
  suspend fun disconnect() {}

  suspend fun listDashboards(): List<DashboardSummary>

  suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot>

  /**
   * When the snapshot the last [loadDashboard] for [urlPath] returned was fetched from Home
   * Assistant, in epoch milliseconds — or null when it was fetched by that call. A session that
   * serves a cached snapshot (the app backgrounded, the network down) answers with the cache's
   * time, so a consumer that stamps values (the Wear sync) can say how old they really are instead
   * of stamping them as fresh.
   */
  fun snapshotFetchedAtMs(urlPath: String?): Long? = null

  /**
   * A dashboard's Lovelace config as Home Assistant stores it, for an agent to edit. Null for
   * sessions that can't read it (demo, offline).
   */
  suspend fun fetchDashboardConfig(urlPath: String?): JsonObject? = null

  /**
   * Save [config] as a dashboard's Lovelace config in Home Assistant and return the config it
   * replaced, for undo. Sessions that can't write to Home Assistant refuse.
   */
  suspend fun replaceDashboardConfig(urlPath: String?, config: JsonObject): JsonObject =
    throw UnsupportedOperationException("This session can't save dashboards to Home Assistant.")

  /**
   * Fetch HA's `get_config` payload — version + `internal_url` / `external_url`. Live sessions
   * round-trip the WebSocket; demo / offline sessions return null so callers can skip the
   * persist-public-URL side effect.
   */
  suspend fun fetchInstanceConfig(): HaInstanceConfig? = null

  /**
   * Fire-and-await an HA service call. The dashboard's `HaActionDispatcher` invokes this when a
   * Lovelace tap action decodes to `CallService` / `Toggle`. Default does nothing so sessions that
   * can't talk to HA (demo, tests) opt out cleanly.
   */
  suspend fun callService(
    domain: String,
    service: String,
    entityId: String? = null,
    serviceData: JsonObject = JsonObject(emptyMap()),
  ): Unit = Unit

  /**
   * Fetch recorder history for [entityIds] over the last [hours]. The card-history screen calls
   * this to draw a chart for whatever entities a card references. Keyed by entity id; each value is
   * the entity's state changes in chronological order. Sessions that can't reach HA's recorder
   * (offline) default to empty.
   */
  suspend fun fetchHistory(
    entityIds: List<String>,
    hours: Int = 24,
  ): Map<String, List<HistoryPoint>> = emptyMap()

  /**
   * Dismiss a single persistent notification by id — removes it from HA's bell-icon list. Live
   * sessions round-trip `persistent_notification.dismiss`; HA then re-broadcasts the shortened list
   * over the subscription. Sessions that can't observe notifications (demo, offline) no-op.
   */
  suspend fun dismissNotification(notificationId: String): Unit = Unit

  /**
   * Clear every persistent notification at once (`persistent_notification.dismiss_all`). Default
   * no-op for sessions with nothing to clear.
   */
  suspend fun dismissAllNotifications(): Unit = Unit

  suspend fun close()
}

enum class SessionConnectionStatus {
  Failed,
  Connecting,
  Connected,

  /**
   * Intentionally parked — the app backgrounded and we closed the socket cleanly. Distinct from
   * [Failed] so the log paints it as a neutral "Disconnected" rather than a red "Error", and so the
   * foreground reconnect hook knows to reopen it.
   */
  Disconnected,
}

/**
 * Live session backed by an HA WebSocket.
 *
 * Each connect asks [tokens] for an access token, so a session outliving its ~30 minute token mints
 * a new one from the stored refresh token instead of failing every reconnect with `auth_invalid`.
 */
class LiveHaSession(
  override val baseUrl: String,
  private val tokens: HaAccessTokens,
  engine: HttpClientEngine? = null,
) : HaSession {
  /** A session pinned to one access token, never refreshed. */
  constructor(
    baseUrl: String,
    accessToken: String,
    engine: HttpClientEngine? = null,
  ) : this(baseUrl, HaAccessTokens(accessToken), engine)

  override val accessToken: String?
    get() = tokens.current

  override val needsReauth: StateFlow<Boolean>
    get() = tokens.needsReauth

  // The token the latest connect offered, so an `auth_invalid` reply invalidates that one.
  @Volatile private var offeredToken: String? = null

  private val client =
    HaClient(
      HaConfig(baseUrl = baseUrl, accessToken = tokens.current.orEmpty()),
      engine = engine,
      accessTokenProvider = { tokens.get().also { offeredToken = it } },
    )
  private val _liveStates = MutableStateFlow<HaSnapshot?>(null)
  override val liveStates: StateFlow<HaSnapshot?> = _liveStates.asStateFlow()
  private val _connectionStatus = MutableStateFlow(SessionConnectionStatus.Connecting)
  override val connectionStatus: StateFlow<SessionConnectionStatus> = _connectionStatus
  private val _notifications = MutableStateFlow<List<HaNotification>>(emptyList())
  override val notifications: StateFlow<List<HaNotification>> = _notifications.asStateFlow()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // True while we've intentionally parked the socket (app backgrounded
  // via [disconnect]). Gates the state bridge so the HaClient.Disconnected
  // that follows surfaces as a clean [SessionConnectionStatus.Disconnected]
  // rather than [Failed] — an *unexpected* mid-flight death (parked ==
  // false) still maps to Failed so the reconnect loop wakes up.
  @Volatile private var parked = false

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
          HaClient.ConnectionState.Disconnected ->
            _connectionStatus.value =
              if (parked) SessionConnectionStatus.Disconnected else SessionConnectionStatus.Failed
          HaClient.ConnectionState.Error -> _connectionStatus.value = SessionConnectionStatus.Failed
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
            runCatching { _notifications.value = client.fetchPersistentNotifications() }
          }
      }
    }

    // Live entity states, as the shared Wasm / desktop host does: on every Ready transition
    // subscribe to `state_changed`, fetch every state once, then patch it per event. Each socket
    // gets its own subscription, so a reconnect re-arms it; until then the feed reads null rather
    // than a stale connection's states.
    scope.launch {
      client.state.collectLatest { state ->
        if (state != HaClient.ConnectionState.Ready) {
          _liveStates.value = null
          return@collectLatest
        }
        try {
          val events = client.subscribeEvents("state_changed")
          stateSnapshots(events) { client.snapshot().copy(devices = devices()) }
            .collect { _liveStates.value = it }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Without the feed cards would silently go stale. Drop the socket, as the shared host
          // does, so the app's reconnect loop re-arms it on a fresh connection.
          _liveStates.value = null
          runCatching { client.disconnect() }
        }
      }
    }
  }

  override suspend fun connect() {
    parked = false
    _connectionStatus.value = SessionConnectionStatus.Connecting
    try {
      try {
        client.connect()
      } catch (e: HaAuthInvalidException) {
        // The token expired or was revoked: drop it and connect once more, which mints a fresh one
        // from the refresh token. A session pinned to one token fails here as before.
        if (!tokens.canRefresh) throw e
        offeredToken?.let { tokens.invalidate(it) }
        client.connect()
      }
      _connectionStatus.value = SessionConnectionStatus.Connected
    } catch (e: Throwable) {
      _connectionStatus.value = SessionConnectionStatus.Failed
      throw e
    }
  }

  override suspend fun disconnect() {
    parked = true
    // Published before the socket goes, so nothing picks this session as connected while it closes.
    _connectionStatus.value = SessionConnectionStatus.Disconnected
    runCatching { client.disconnect() }
    _connectionStatus.value = SessionConnectionStatus.Disconnected
  }

  override suspend fun listDashboards(): List<DashboardSummary> = client.listDashboards()

  override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
    coroutineScope {
      // Read alongside the dashboard and states, never in front of them; bounded and best effort.
      val unit = async { temperatureUnit() }
      val dashboard = client.fetchDashboard(urlPath)
      val snapshot = client.snapshot().copy(devices = devices(), temperatureUnit = unit.await())
      dashboard to snapshot
    }

  // The instance's unit system: asked once a session. An instance that doesn't answer (or refuses
  // get_config) isn't asked again, so it never costs a reload or reconnect a wait; its cards use
  // the
  // entity's own unit, then °C.
  @Volatile private var temperatureUnitAsked = false
  @Volatile private var temperatureUnitCache: String? = null

  private suspend fun temperatureUnit(): String? {
    if (temperatureUnitAsked) return temperatureUnitCache
    return client.fetchTemperatureUnit().also {
      temperatureUnitCache = it
      temperatureUnitAsked = true
    }
  }

  // Which device is behind each entity changes rarely: read once a session. A failure (an older
  // Home Assistant, a refused registry) leaves cards on their generic designs and is retried.
  @Volatile private var deviceCache: Map<String, ee.schimke.ha.design.DeviceInfo>? = null

  private suspend fun devices(): Map<String, ee.schimke.ha.design.DeviceInfo> =
    deviceCache
      ?: runCatching { client.fetchDevices() }.getOrNull()?.also { deviceCache = it }
      ?: emptyMap()

  override suspend fun fetchInstanceConfig(): HaInstanceConfig = client.fetchConfig()

  override suspend fun fetchDashboardConfig(urlPath: String?): JsonObject =
    client.fetchDashboardConfig(urlPath)

  override suspend fun replaceDashboardConfig(urlPath: String?, config: JsonObject): JsonObject =
    client.replaceDashboardConfig(config, urlPath)

  override suspend fun callService(
    domain: String,
    service: String,
    entityId: String?,
    serviceData: JsonObject,
  ) = client.callService(domain, service, entityId, serviceData)

  override suspend fun fetchHistory(
    entityIds: List<String>,
    hours: Int,
  ): Map<String, List<HistoryPoint>> {
    val end = Clock.System.now()
    return client.fetchHistory(entityIds, end - hours.hours, end)
  }

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
