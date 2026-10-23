package ee.schimke.terrazzo.wear.widget

import android.content.Context
import ee.schimke.ha.rc.FileLocalDesignStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The container-neutral Wear templates ([neutralWidgetTemplate]) on disk, so a new process — a
 * widget update, or the app's first frame after a restart — reads one instead of capturing it
 * again. A capture depends on the app's own converters and on the display's density and font scale,
 * so templates live under a directory named for all three, as [FileLocalDesignStore] keeps the
 * design library's (this install's update time stands for the build); [prune] deletes any other.
 * Templates are read one at a time, as they are asked for.
 */
internal class WearNeutralTemplateStore(
  private val root: File,
  /** The directory name for what captures depend on now; read on every access. */
  private val scope: () -> String,
) {
  private fun file(name: String) = File(File(root, scope()), "$name.rc")

  /** The template stored as [name], or null when there is none (or it can't be read). */
  fun read(name: String): ByteArray? = runCatching {
    file(name).takeIf { it.isFile }?.readBytes()?.takeIf { it.isNotEmpty() }
  }
    .getOrNull()

  /** Stores [bytes] as [name]: whole or not at all, as another process may read it meanwhile. */
  fun write(name: String, bytes: ByteArray) {
    runCatching {
      val target = file(name)
      val dir = requireNotNull(target.parentFile).apply { mkdirs() }
      val tmp = File.createTempFile("${target.name}.", ".tmp", dir)
      try {
        tmp.writeBytes(bytes)
        Files.move(
          tmp.toPath(),
          target.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } finally {
        tmp.delete()
      }
    }
  }

  /**
   * Deletes every directory but the current scope's: templates an earlier build or display made.
   */
  fun prune() = FileLocalDesignStore(root, scope).prune()

  companion object {
    @Volatile private var shared: Pair<Context, WearNeutralTemplateStore>? = null

    /**
     * This app's store, under `files/terrazzo/wear/neutral-templates`: one per process (per
     * application, for tests that each get a new one), as looking up the install time isn't free.
     */
    fun of(context: Context): WearNeutralTemplateStore {
      val app = context.applicationContext ?: context
      shared
        ?.takeIf { it.first === app }
        ?.let {
          return it.second
        }
      val installed by lazy {
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }
          .getOrDefault(0L)
      }
      val store =
        WearNeutralTemplateStore(File(app.filesDir, "terrazzo/wear/neutral-templates")) {
          val configuration = app.resources.configuration
          "$installed-${configuration.densityDpi}-${configuration.fontScale}"
        }
      shared = app to store
      return store
    }
  }
}
