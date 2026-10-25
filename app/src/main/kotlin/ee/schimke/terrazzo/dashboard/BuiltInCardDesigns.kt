package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.util.Log
import ee.schimke.ha.client.design.BundledDesigns
import java.io.File
import java.io.FileNotFoundException

/**
 * The designs the app ships for its built-in card styles ([CardStyles.BUILT_IN]): Classic in the
 * Material 3 palette and Home Assistant, every shape the library covers, light and dark. They are
 * generated at build time (`:card-designs:generateBuiltInCardDesigns`) into `assets/card-designs/`,
 * captured as the design server captures them, so each is the same file the server serves for its
 * key and the first sync downloads none of them again.
 *
 * A design cache with no manifest yet is seeded from them ([BundledDesigns.seed]), so the app draws
 * its built-in styles with the design server down, unreachable or turned off; the sync that runs on
 * every start replaces them with the server's library once it answers. Seeding never throws and
 * never waits on the network.
 */
object BuiltInCardDesigns {
  private const val TAG = "BuiltInCardDesigns"

  /** Where the app's assets keep them. */
  const val DIRECTORY = "card-designs"

  /** Seeds the design cache in [dir] when it has no manifest yet; a no-op once it has one. */
  suspend fun seed(context: Context, dir: File) {
    if (File(dir, "manifest.json").isFile) return
    val assets = context.applicationContext.assets
    runCatching {
      BundledDesigns.seed(FileDesignStore(dir)) { path ->
        try {
          assets.open("$DIRECTORY/$path").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
          null
        }
      }
    }
      .onFailure { Log.w(TAG, "seeding the built-in card designs failed", it) }
  }
}
