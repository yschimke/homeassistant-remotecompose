package ee.schimke.ha.client.cache

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint

/**
 * What `CachedHaSession` (in `terrazzo-core`) keeps between runs, per Home Assistant instance
 * ([baseUrl][setLastInstance]): the dashboards, each dashboard's last snapshot, and the history
 * charts last drawn. Every read returns null for what was never stored.
 *
 * Common so any target can be offline-first. `OfflineCacheStorage` is the file-backed one, on
 * Android (`terrazzo-core`'s `OfflineCache`) and the desktop; a browser host supplies its own.
 */
interface OfflineCacheStore {
  /** Records [baseUrl] as the instance a cold start resumes. */
  fun setLastInstance(baseUrl: String)

  fun dashboards(baseUrl: String): List<DashboardSummary>?

  fun putDashboards(baseUrl: String, list: List<DashboardSummary>)

  fun dashboard(baseUrl: String, urlPath: String?): Dashboard?

  fun putDashboard(baseUrl: String, urlPath: String?, dashboard: Dashboard)

  fun snapshot(baseUrl: String, urlPath: String?): HaSnapshot?

  /** When [snapshot] was stored, in epoch milliseconds; null when unknown. */
  fun snapshotSavedAtMs(baseUrl: String, urlPath: String?): Long?

  fun putSnapshot(baseUrl: String, urlPath: String?, snapshot: HaSnapshot)

  /**
   * The last history read for [entityIds] over [hours]; the order of [entityIds] doesn't matter.
   */
  fun history(
    baseUrl: String,
    entityIds: List<String>,
    hours: Int,
  ): Map<String, List<HistoryPoint>>?

  fun putHistory(
    baseUrl: String,
    entityIds: List<String>,
    hours: Int,
    history: Map<String, List<HistoryPoint>>,
  )
}
