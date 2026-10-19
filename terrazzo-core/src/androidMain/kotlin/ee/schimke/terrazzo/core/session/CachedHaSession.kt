package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaInstanceConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaNotification
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.cache.OfflineCacheStorage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Offline-first wrapper around any [HaSession]. Every successful live fetch is mirrored to the
 * [OfflineCacheStorage]; every read falls back to the cache when the live call fails, and the cache
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
class CachedHaSession(private val delegate: HaSession, private val cache: OfflineCacheStorage) :
  HaSession {

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
    runCatching { delegate.connect() }
    // Even if connect() failed, mark this as the most-recent instance
    // so cold-start auto-resume can find it. We have either a cached
    // payload or we'll get one when the network returns.
    cache.setLastInstance(baseUrl)
  }

  override suspend fun listDashboards(): List<DashboardSummary> {
    val cached = cache.dashboards(baseUrl)
    return runCatching { delegate.listDashboards() }
      .onSuccess { fresh -> cache.putDashboards(baseUrl, fresh) }
      .getOrElse { ex -> cached ?: throw ex }
  }

  override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> {
    val cached =
      cache.dashboard(baseUrl, urlPath)?.let { d ->
        d to (cache.snapshot(baseUrl, urlPath) ?: HaSnapshot())
      }
    return runCatching { delegate.loadDashboard(urlPath) }
      .onSuccess { (dashboard, snapshot) ->
        liveFetchedAtMs[urlPath.orEmpty()] = System.currentTimeMillis()
        servedFromCache.remove(urlPath.orEmpty())
        cache.putDashboard(baseUrl, urlPath, dashboard)
        cache.putSnapshot(baseUrl, urlPath, snapshot)
      }
      .getOrElse { ex -> (cached ?: throw ex).also { servedFromCache.add(urlPath.orEmpty()) } }
  }

  /** Per dashboard (`""` for the default): when this session last fetched its snapshot live. */
  private val liveFetchedAtMs = ConcurrentHashMap<String, Long>()

  /** The dashboards whose last [loadDashboard] fell back to the cache. */
  private val servedFromCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /**
   * Null after a live fetch; after a fallback, when the cached snapshot was fetched — this
   * session's own last live fetch if it made one, else the cache file's write time (0 when even
   * that is unknown, so it reads as old rather than fresh).
   */
  override fun snapshotFetchedAtMs(urlPath: String?): Long? {
    val key = urlPath.orEmpty()
    if (key !in servedFromCache) return null
    return liveFetchedAtMs[key] ?: cache.snapshotSavedAtMs(baseUrl, urlPath) ?: 0L
  }

  override suspend fun fetchInstanceConfig(): HaInstanceConfig? = delegate.fetchInstanceConfig()

  // History is a live-only, on-demand read (the card-history screen) — we
  // don't cache it, but we must forward so the wrapped live session
  // actually hits HA's recorder instead of the no-op interface default.
  override suspend fun fetchHistory(
    entityIds: List<String>,
    hours: Int,
  ): Map<String, List<ee.schimke.ha.model.HistoryPoint>> = delegate.fetchHistory(entityIds, hours)

  override suspend fun fetchDashboardConfig(urlPath: String?): JsonObject? =
    delegate.fetchDashboardConfig(urlPath)

  override suspend fun replaceDashboardConfig(urlPath: String?, config: JsonObject): JsonObject =
    delegate.replaceDashboardConfig(urlPath, config)

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
}
