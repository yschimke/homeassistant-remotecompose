package ee.schimke.terrazzo.tv.data

import android.content.Context
import java.io.File

/**
 * Offline-first scaffold for the TV companion. Today the TV app is a
 * kiosk + demo surface with no live HA wiring; when a `TvHaSession`
 * lands later it should read from / write to this cache so a wall-
 * mounted screen that loses the LAN keeps showing its last good
 * dashboard rather than blanking.
 *
 * Layout under the TV app's `filesDir/terrazzo/tv/`:
 *
 * ```
 *   instance.json                   — { baseUrl } most-recent instance
 *   <baseUrl-hash>/
 *     dashboards.json
 *     dashboard/<urlPath>.json
 *     snapshot/<urlPath>.json
 * ```
 *
 * Same shape as the mobile [ee.schimke.terrazzo.core.cache.OfflineCache];
 * we duplicate rather than depend on `terrazzo-core` because the TV
 * module's minSdk (29) is lower than terrazzo-core's (35), and adding
 * the dep would force terrazzo-core's API floor up. When the TV app
 * lands a real HA session we can revisit lifting the floor.
 *
 * The cache stores opaque JSON strings keyed by name — callers own the
 * serialization since the TV module deliberately avoids depending on
 * `ha-model` until it has a use for it.
 */
class TvOfflineCache(context: Context) {

  private val root: File = File(context.filesDir, "terrazzo/tv").apply { mkdirs() }

  /** Fetch a previously-stored JSON blob by [scope]/[name]. Returns null if absent. */
  fun read(scope: String, name: String): String? =
    fileFor(scope, name).takeIf { it.exists() }?.readText()

  /** Persist [json] under [scope]/[name]. Atomic via tmp+rename. */
  fun write(scope: String, name: String, json: String) {
    val target = fileFor(scope, name)
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(json)
    if (!tmp.renameTo(target)) {
      target.writeText(json)
      tmp.delete()
    }
  }

  /** Wipe a [scope] (e.g. one HA instance directory). */
  fun clearScope(scope: String) {
    File(root, scope).deleteRecursively()
  }

  private fun fileFor(scope: String, name: String): File =
    File(File(root, scope), name)
}
