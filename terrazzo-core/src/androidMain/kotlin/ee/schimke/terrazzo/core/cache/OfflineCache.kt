package ee.schimke.terrazzo.core.cache

import android.content.Context
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.di.AppScope
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persistent offline cache for the per-instance Home Assistant payloads
 * the UI reads (dashboard list, dashboard configs, entity snapshots).
 *
 * Once the user has logged in and the app has fetched data once, every
 * subsequent launch must render the last-known data without a network.
 * `CachedHaSession` writes here on each successful live fetch; the UI
 * reads here first and then upgrades to live data when it arrives.
 *
 * Layout under `filesDir/terrazzo/`:
 *
 * ```
 *   instance.json                       — { baseUrl } most-recent instance
 *   cache/<sha-baseUrl>/dashboards.json — List<DashboardSummary>
 *   cache/<sha-baseUrl>/dashboard/<urlPath>.json — Dashboard
 *   cache/<sha-baseUrl>/snapshot/<urlPath>.json  — HaSnapshot
 * ```
 *
 * `urlPath` may be null (HA's default dashboard) — encoded as the file
 * name `_default` to match the wear-sync proto convention.
 *
 * Demo mode is intentionally not cached here: its session derives
 * dashboards + snapshot deterministically from `DemoData`, so a cache
 * lookup would always be redundant.
 */
@SingleIn(AppScope::class)
@Inject
class OfflineCache(context: Context) :
  OfflineCacheStorage(File(context.filesDir, "terrazzo"))

/**
 * File-backed storage layer. Split out from [OfflineCache] so JVM unit
 * tests can construct against a temp directory without bringing in
 * Robolectric.
 */
open class OfflineCacheStorage(rootDir: File) {

  private val root: File = rootDir.apply { mkdirs() }
  private val cacheRoot: File = File(root, "cache").apply { mkdirs() }
  private val instanceFile: File = File(root, "instance.json")
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  /**
   * The instance the user was last connected to. Used by
   * `MainActivity` to decide whether to auto-resume on cold start
   * versus showing the discovery / login screens.
   */
  fun lastInstance(): String? =
    runCatching {
        if (!instanceFile.exists()) return@runCatching null
        json.decodeFromString(InstancePointer.serializer(), instanceFile.readText()).baseUrl
      }
      .getOrNull()

  fun setLastInstance(baseUrl: String) {
    val payload = json.encodeToString(InstancePointer.serializer(), InstancePointer(baseUrl))
    instanceFile.atomicWriteText(payload)
  }

  fun clearLastInstance() {
    instanceFile.delete()
  }

  /** Per-instance dashboard listing. */
  fun dashboards(baseUrl: String): List<DashboardSummary>? =
    readJson(dashboardsFile(baseUrl), ListSerializer(DashboardSummary.serializer()))

  fun putDashboards(baseUrl: String, list: List<DashboardSummary>) {
    writeJson(dashboardsFile(baseUrl), ListSerializer(DashboardSummary.serializer()), list)
  }

  /** Per-instance, per-dashboard config. `urlPath == null` is the default dashboard. */
  fun dashboard(baseUrl: String, urlPath: String?): Dashboard? =
    readJson(dashboardFile(baseUrl, urlPath), Dashboard.serializer())

  fun putDashboard(baseUrl: String, urlPath: String?, dashboard: Dashboard) {
    writeJson(dashboardFile(baseUrl, urlPath), Dashboard.serializer(), dashboard)
  }

  /** Per-instance, per-dashboard entity snapshot. */
  fun snapshot(baseUrl: String, urlPath: String?): HaSnapshot? =
    readJson(snapshotFile(baseUrl, urlPath), HaSnapshot.serializer())

  fun putSnapshot(baseUrl: String, urlPath: String?, snapshot: HaSnapshot) {
    writeJson(snapshotFile(baseUrl, urlPath), HaSnapshot.serializer(), snapshot)
  }

  /**
   * Wipe every cached payload for [baseUrl]. Called by sign-out so a
   * subsequent user on the device can't read the previous account's
   * dashboard config off disk.
   */
  fun clearInstance(baseUrl: String) {
    instanceDir(baseUrl).deleteRecursively()
    if (lastInstance() == baseUrl) clearLastInstance()
  }

  private fun <T> readJson(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? =
    runCatching {
        if (!file.exists()) return@runCatching null
        json.decodeFromString(serializer, file.readText())
      }
      .getOrNull()

  private fun <T> writeJson(
    file: File,
    serializer: kotlinx.serialization.KSerializer<T>,
    value: T,
  ) {
    file.parentFile?.mkdirs()
    file.atomicWriteText(json.encodeToString(serializer, value))
  }

  private fun instanceDir(baseUrl: String): File = File(cacheRoot, instanceKey(baseUrl))

  private fun dashboardsFile(baseUrl: String): File = File(instanceDir(baseUrl), "dashboards.json")

  private fun dashboardFile(baseUrl: String, urlPath: String?): File =
    File(File(instanceDir(baseUrl), "dashboard"), "${urlPath.encodeUrlPath()}.json")

  private fun snapshotFile(baseUrl: String, urlPath: String?): File =
    File(File(instanceDir(baseUrl), "snapshot"), "${urlPath.encodeUrlPath()}.json")

  @kotlinx.serialization.Serializable private data class InstancePointer(val baseUrl: String)

  companion object {
    /** SHA-1 of the baseUrl as the directory name — keeps any URL-shaped filesystem. */
    fun instanceKey(baseUrl: String): String {
      val md = MessageDigest.getInstance("SHA-1")
      val digest = md.digest(baseUrl.trim().removeSuffix("/").toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }
    }
  }
}

/** Mirror of `WearSyncPaths.dashboardPath`'s null encoding. */
private fun String?.encodeUrlPath(): String =
  this?.takeIf { it.isNotEmpty() }?.replace('/', '_') ?: "_default"

/**
 * Atomic write — render to a sibling .tmp first then rename, so a kill
 * mid-write doesn't leave a half-written cache file that decodes as
 * partial data on next launch.
 */
private fun File.atomicWriteText(text: String) {
  val tmp = File(parentFile, "$name.tmp")
  tmp.writeText(text)
  if (!tmp.renameTo(this)) {
    writeText(text)
    tmp.delete()
  }
}
