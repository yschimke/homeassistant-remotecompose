package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stub session whose live calls always fail with [OfflineUnavailable]. Wrapped by [CachedHaSession]
 * during cold-start auto-resume — the cache supplies the data the UI renders, and as soon as the
 * access token is minted (or the user re-logs-in) the session is swapped for a real
 * [LiveHaSession]-backed one.
 */
internal class OfflineOnlySession(override val baseUrl: String) : HaSession {
  override val refreshIntervalMillis: Long? = null

  // Connecting until the resume gives up: the stub stands in for a session being minted, so a cold
  // start opened on "Can't reach Home Assistant" for the moment before the live one replaced it.
  private val status = MutableStateFlow(SessionConnectionStatus.Connecting)
  override val connectionStatus: StateFlow<SessionConnectionStatus> = status

  /** The resume couldn't mint a live session: no stored token, or Home Assistant unreachable. */
  fun markUnreachable() {
    status.value = SessionConnectionStatus.Failed
  }

  override suspend fun connect() = Unit

  override suspend fun listDashboards(): List<DashboardSummary> = throw OfflineUnavailable

  override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
    throw OfflineUnavailable

  override suspend fun close() = Unit
}

internal object OfflineUnavailable : RuntimeException("offline — no live HA connection")
