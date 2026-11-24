package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaInstanceConfig
import ee.schimke.ha.client.agent.AgentGrantStore
import ee.schimke.ha.client.cache.OfflineCacheStore
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaNotification
import ee.schimke.ha.model.HaSnapshot
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/**
 * Offline-first wrapper around any [HaSession]. Every successful live fetch is mirrored to the
 * [OfflineCacheStore]; every read falls back to the cache when the live call fails, and the cache
 * is the first thing the UI sees on cold start.
 *
 * Failure-mode contract:
 * - [listDashboards] / [loadDashboard] **never** throw if a cached value exists. They throw only on
 *   first-ever fetch when the network is also unavailable.
 * - On every successful live fetch the returned value is also persisted via [OfflineCache], so a
 *   subsequent cold start with no network still has data.
 *
 * This wraps both [LiveHaSession] (real HA) and [DemoHaSession] (which is already a deterministic
 * source — wrapping it is a no-op cache write that costs nothing). Demo callers can skip the
 * wrapper if they prefer; production sessions always use it.
 */
class CachedHaSession(
  private val delegate: HaSession,
  private val cache: OfflineCacheStore,
  private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : HaSession, TerrazzoSession {

  override val baseUrl: String
    get() = delegate.baseUrl

  override val accessToken: String?
    get() = delegate.accessToken

  override val refreshIntervalMillis: Long?
    get() = delegate.refreshIntervalMillis

  override val connectionStatus: StateFlow<SessionConnectionStatus>
    get() = delegate.connectionStatus

  override val notifications: StateFlow<List<HaNotification>>
    get() = delegate.notifications

  override val liveStates: StateFlow<HaSnapshot?>
    get() = delegate.liveStates

  override val needsReauth: StateFlow<Boolean>
    get() = delegate.needsReauth

  override suspend fun connect() {
    try {
      delegate.connect()
    } catch (_: Throwable) {
      currentCoroutineContext().ensureActive()
    }
    // Even if connect() failed, mark this as the most-recent instance
    // so cold-start auto-resume can find it. We have either a cached
    // payload or we'll get one when the network returns.
    cache.setLastInstance(baseUrl)
  }

  override suspend fun listDashboards(): List<DashboardSummary> {
    val cached = cache.dashboards(baseUrl)
    return try {
      delegate.listDashboards().also { fresh -> cache.putDashboards(baseUrl, fresh) }
    } catch (ex: Throwable) {
      currentCoroutineContext().ensureActive()
      cached ?: throw ex
    }
  }

  override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> {
    val cached =
      cache.dashboard(baseUrl, urlPath)?.let { d ->
        d to (cache.snapshot(baseUrl, urlPath) ?: HaSnapshot())
      }
    return try {
      delegate
        .loadDashboard(urlPath)
        .let { (d, fresh) -> d to fresh.keeping(cached?.second) }
        .also { (dashboard, snapshot) ->
          val key = urlPath.orEmpty()
          liveFetchedAtMs.update { it + (key to nowMs()) }
          servedFromCache.update { it - key }
          cache.putDashboard(baseUrl, urlPath, dashboard)
          cache.putSnapshot(baseUrl, urlPath, snapshot)
        }
    } catch (ex: Throwable) {
      currentCoroutineContext().ensureActive()
      (cached ?: throw ex).also { servedFromCache.update { it + urlPath.orEmpty() } }
    }
  }

  /**
   * The states alone, as the default dashboard's snapshot is cached (the same states): live, else
   * the cached copy, which [snapshotFetchedAtMs] with a null url path then dates.
   */
  override suspend fun loadSnapshot(): HaSnapshot =
    try {
      delegate.loadSnapshot().keeping(cache.snapshot(baseUrl, null)).also { snapshot ->
        liveFetchedAtMs.update { it + ("" to nowMs()) }
        servedFromCache.update { it - "" }
        cache.putSnapshot(baseUrl, null, snapshot)
      }
    } catch (ex: Throwable) {
      currentCoroutineContext().ensureActive()
      (cache.snapshot(baseUrl, null) ?: throw ex).also { servedFromCache.update { it + "" } }
    }

  /**
   * Keeps the stored snapshot's per-instance facts where this live read came back without them: the
   * device registry and unit system are read best effort, and a transient failure there must not
   * overwrite good cached values, which later offline starts would then draw without.
   */
  private fun HaSnapshot.keeping(stored: HaSnapshot?): HaSnapshot {
    if (stored == null) return this
    return copy(
      devices = devices.ifEmpty { stored.devices },
      temperatureUnit = temperatureUnit ?: stored.temperatureUnit,
    )
  }

  /** Per dashboard (`""` for the default): when this session last fetched its snapshot live. */
  // Copy-on-write maps rather than ConcurrentHashMap, which is JVM-only: written once per fetch,
  // read once per frame at most.
  private val liveFetchedAtMs = MutableStateFlow<Map<String, Long>>(emptyMap())

  /** The dashboards whose last [loadDashboard] fell back to the cache. */
  private val servedFromCache = MutableStateFlow<Set<String>>(emptySet())

  /**
   * Null after a live fetch; after a fallback, when the cached snapshot was fetched — this
   * session's own last live fetch if it made one, else the cache file's write time (0 when even
   * that is unknown, so it reads as old rather than fresh).
   */
  override fun snapshotFetchedAtMs(urlPath: String?): Long? {
    val key = urlPath.orEmpty()
    if (key !in servedFromCache.value) return null
    return liveFetchedAtMs.value[key] ?: cache.snapshotSavedAtMs(baseUrl, urlPath) ?: 0L
  }

  override suspend fun fetchInstanceConfig(): HaInstanceConfig? = delegate.fetchInstanceConfig()

  override suspend fun createLongLivedToken(clientName: String, lifespanDays: Int): String? =
    delegate.createLongLivedToken(clientName, lifespanDays)

  override suspend fun deleteLongLivedTokens(matches: (clientName: String) -> Boolean): Int =
    delegate.deleteLongLivedTokens(matches)

  // History is an on-demand read (the card-history screen): live from HA's recorder when it can,
  // else the last chart read for the same entities and window, so it opens offline too. Forwarded
  // explicitly; the interface default is a no-op.
  override suspend fun fetchHistory(
    entityIds: List<String>,
    hours: Int,
  ): Map<String, List<ee.schimke.ha.model.HistoryPoint>> =
    try {
      delegate.fetchHistory(entityIds, hours).also {
        if (it.isNotEmpty()) cache.putHistory(baseUrl, entityIds, hours, it)
      }
    } catch (ex: Throwable) {
      currentCoroutineContext().ensureActive()
      cache.history(baseUrl, entityIds, hours) ?: throw ex
    }

  override suspend fun fetchDashboardConfig(urlPath: String?): JsonObject? =
    delegate.fetchDashboardConfig(urlPath)

  override suspend fun replaceDashboardConfig(urlPath: String?, config: JsonObject): JsonObject =
    delegate.replaceDashboardConfig(urlPath, config)

  override suspend fun compareAndReplaceDashboardConfig(
    urlPath: String?,
    expected: JsonObject,
    config: JsonObject,
  ) = delegate.compareAndReplaceDashboardConfig(urlPath, expected, config)

  // Writes aren't cached — forward straight to the delegate. Without this
  // the interface's no-op default would swallow every service call when a
  // live session is wrapped (the production path), so taps would silently
  // do nothing.
  override suspend fun callService(
    domain: String,
    service: String,
    entityId: String?,
    serviceData: JsonObject,
  ) = delegate.callService(domain, service, entityId, serviceData)

  override suspend fun dismissNotification(notificationId: String) =
    delegate.dismissNotification(notificationId)

  override suspend fun dismissAllNotifications() = delegate.dismissAllNotifications()

  override suspend fun disconnect() {
    delegate.disconnect()
  }

  override suspend fun close() {
    delegate.close()
  }

  companion object {
    /**
     * A session with no live connection behind it, only [cache]: what the UI draws until a live
     * session replaces it. Every read is the cached copy; every live-only call fails.
     */
    fun cacheOnly(baseUrl: String, cache: OfflineCacheStore): CachedHaSession =
      CachedHaSession(OfflineOnlySession(baseUrl), cache)
  }

  override fun pushNotifications(webhookId: String) = delegate.pushNotifications(webhookId)

  override suspend fun fireEvent(eventType: String, eventData: JsonObject) =
    delegate.fireEvent(eventType, eventData)

  // The Terrazzo integration lives in Home Assistant; there is nothing to cache. Around a session
  // that can't reach it (cacheOnly), each answers as without the integration.
  private val terrazzo: TerrazzoSession?
    get() = delegate.terrazzo

  override suspend fun terrazzoIntegration() = terrazzo?.terrazzoIntegration()

  override val agentEdits: AgentEdits?
    get() = terrazzo?.agentEdits

  override suspend fun dashboardAgent() = terrazzo?.dashboardAgent() ?: DashboardAgent.Off

  override fun cloudAgentEdits(
    server: String,
    grants: AgentGrantStore,
    agentLink: () -> String?,
  ) = terrazzo?.cloudAgentEdits(server, grants, agentLink)

  override suspend fun registerSilentPush(webhookId: String, fcmToken: String, relayUrl: String) =
    terrazzo?.registerSilentPush(webhookId, fcmToken, relayUrl)

  override suspend fun takeHeldNotifications(webhookId: String) =
    terrazzo?.takeHeldNotifications(webhookId).orEmpty()

  override suspend fun watchStates(
    webhookId: String,
    entityIds: Collection<String>,
    leaseSeconds: Int,
  ) = terrazzo?.watchStates(webhookId, entityIds, leaseSeconds)

  override suspend fun privateDesignServer() = terrazzo?.privateDesignServer() == true

  override suspend fun sharedDesignSelections() = terrazzo?.sharedDesignSelections()

  override suspend fun setSharedDesignSelection(card: String, designId: String) {
    val live =
      terrazzo ?: throw UnsupportedOperationException("Shared card selections are unavailable")
    live.setSharedDesignSelection(card, designId)
  }

  override suspend fun privateDesignLink(path: String) = terrazzo?.privateDesignLink(path)
}
