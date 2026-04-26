package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot

/**
 * Stub session whose live calls always fail with [OfflineUnavailable].
 * Wrapped by [CachedHaSession] during cold-start auto-resume — the
 * cache supplies the data the UI renders, and as soon as the access
 * token is minted (or the user re-logs-in) the session is swapped for a
 * real [LiveHaSession]-backed one.
 */
internal class OfflineOnlySession(override val baseUrl: String) : HaSession {
  override val refreshIntervalMillis: Long? = null

  override suspend fun connect() = Unit

  override suspend fun listDashboards(): List<DashboardSummary> = throw OfflineUnavailable

  override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
    throw OfflineUnavailable

  override suspend fun close() = Unit
}

internal object OfflineUnavailable : RuntimeException("offline — no live HA connection")
