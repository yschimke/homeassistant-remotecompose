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

  /**
   * For a cache-only session (`TerrazzoGraph.createCachedOnly`): the cold-start resume couldn't
   * replace it with a live one, so its status turns from connecting to failed.
   */
  fun markUnreachable() {
    (delegate as? OfflineOnlySession)?.markUnreachable()
  }

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
    return runCatching {
      delegate.loadDashboard(urlPath).let { (d, fresh) -> d to fresh.keeping(cached?.second) }
    }
      .onSuccess { (dashboard, snapshot) ->
        liveFetchedAtMs[urlPath.orEmpty()] = System.currentTimeMillis()
        servedFromCache.remove(urlPath.orEmpty())
        cache.putDashboard(baseUrl, urlPath, dashboard)
        cache.putSnapshot(baseUrl, urlPath, snapshot)
      }
      .getOrElse { ex -> (cached ?: throw ex).also { servedFromCache.add(urlPath.orEmpty()) } }
  }

  /**
   * The states alone, as the default dashboard's snapshot is cached (the same states): live, else
   * the cached copy, which [snapshotFetchedAtMs] with a null url path then dates.
   */
  override suspend fun loadSnapshot(): HaSnapshot = runCatching {
    delegate.loadSnapshot().keeping(cache.snapshot(baseUrl, null))
  }
    .onSuccess { snapshot ->
      liveFetchedAtMs[""] = System.currentTimeMillis()
      servedFromCache.remove("")
      cache.putSnapshot(baseUrl, null, snapshot)
    }
    .getOrElse { ex ->
      (cache.snapshot(baseUrl, null) ?: throw ex).also { servedFromCache.add("") }
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
  ): Map<String, List<ee.schimke.ha.model.HistoryPoint>> = runCatching {
    delegate.fetchHistory(entityIds, hours)
  }
    .onSuccess { if (it.isNotEmpty()) cache.putHistory(baseUrl, entityIds, hours, it) }
    .getOrElse { ex -> cache.history(baseUrl, entityIds, hours) ?: throw ex }

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

  companion object {
    /**
     * A session with no live connection behind it, only [cache]: what the UI draws until a live
     * session replaces it. Every read is the cached copy; every live-only call fails.
     */
    fun cacheOnly(baseUrl: String, cache: OfflineCacheStorage): CachedHaSession =
      CachedHaSession(OfflineOnlySession(baseUrl), cache)
  }
}
