package ee.schimke.terrazzo.core.cache

import android.content.Context
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.client.cache.OfflineCacheStorage
import ee.schimke.terrazzo.core.di.AppScope
import java.io.File

/**
 * Persistent offline cache for the per-instance Home Assistant payloads the UI reads (dashboard
 * list, dashboard configs, entity snapshots).
 *
 * Once the user has logged in and the app has fetched data once, every subsequent launch must
 * render the last-known data without a network. `CachedHaSession` writes here on each successful
 * live fetch; the UI reads here first and then upgrades to live data when it arrives.
 *
 * Layout under `filesDir/terrazzo/`:
 * ```
 *   instance.json                       — { baseUrl } most-recent instance
 *   cache/<sha-baseUrl>/dashboards.json — List<DashboardSummary>
 *   cache/<sha-baseUrl>/dashboard/<urlPath>.json — Dashboard
 *   cache/<sha-baseUrl>/snapshot/<urlPath>.json  — HaSnapshot
 * ```
 *
 * `urlPath` may be null (HA's default dashboard) — encoded as the file name `_default` to match the
 * wear-sync proto convention.
 *
 * Demo mode is intentionally not cached here: its session derives dashboards + snapshot
 * deterministically from `DemoData`, so a cache lookup would always be redundant.
 */
@SingleIn(AppScope::class)
@Inject
class OfflineCache(context: Context) : OfflineCacheStorage(File(context.filesDir, "terrazzo"))
