package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.client.HaInstanceConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaNotification
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.cache.OfflineCacheStorage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Offline-first wrapper around any [HaSession]. Every successful live
 * fetch is mirrored to the [OfflineCacheStorage]; every read falls back
 * to the cache when the live call fails, and the cache is the first
 * thing the UI sees on cold start.
 *
 * Failure-mode contract:
 *   - [listDashboards] / [loadDashboard] **never** throw if a cached
 *     value exists. They throw only on first-ever fetch when the
 *     network is also unavailable.
 *   - On every successful live fetch the returned value is also
 *     persisted via [OfflineCache], so a subsequent cold start with no
 *     network still has data.
 *
 * This wraps both [LiveHaSession] (real HA) and [DemoHaSession] (which
 * is already a deterministic source — wrapping it is a no-op cache
 * write that costs nothing). Demo callers can skip the wrapper if they
 * prefer; production sessions always use it.
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
    val cached = cache.dashboard(baseUrl, urlPath)?.let { d ->
      d to (cache.snapshot(baseUrl, urlPath) ?: HaSnapshot())
    }
    return runCatching { delegate.loadDashboard(urlPath) }
      .onSuccess { (dashboard, snapshot) ->
        cache.putDashboard(baseUrl, urlPath, dashboard)
        cache.putSnapshot(baseUrl, urlPath, snapshot)
      }
      .getOrElse { ex -> cached ?: throw ex }
  }

  override suspend fun fetchInstanceConfig(): HaInstanceConfig? = delegate.fetchInstanceConfig()

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

  override suspend fun close() {
    delegate.close()
  }
}
