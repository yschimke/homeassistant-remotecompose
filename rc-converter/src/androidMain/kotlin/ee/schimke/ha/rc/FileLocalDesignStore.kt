package ee.schimke.ha.rc

import android.content.Context
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignKey
import java.io.File
import kotlinx.serialization.json.Json

/**
 * The local library's templates on disk ([CardDesignLibrary.store]), so a key the server lacks is
 * captured once rather than on every start. A capture depends on the app's own converters and on
 * the display's density and font scale, so templates live under a directory named for all three
 * (this install's update time stands for the build); any other directory is stale and [prune]
 * deletes it.
 */
class FileLocalDesignStore(
  private val root: File,
  /** The directory name for what captures depend on now; read on every access. */
  private val scope: () -> String,
) : LocalDesignStore {
  private val json = Json

  private fun scopeDir(): File = File(root, scope())

  private fun file(key: DesignKey): File {
    val name = Sha256.hex(json.encodeToString(DesignKey.serializer(), key).encodeToByteArray())
    return File(scopeDir(), "$name.rc")
  }

  override fun read(key: DesignKey): ByteArray? = runCatching {
    file(key).takeIf { it.isFile }?.readBytes()
  }
    .getOrNull()

  override fun write(key: DesignKey, bytes: ByteArray) {
    runCatching {
      val target = file(key)
      val dir = requireNotNull(target.parentFile).apply { mkdirs() }
      val tmp = File.createTempFile("${target.name}.", ".tmp", dir)
      try {
        tmp.writeBytes(bytes)
        java.nio.file.Files.move(
          tmp.toPath(),
          target.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
      } finally {
        tmp.delete()
      }
    }
  }

  /**
   * Deletes every directory but the current scope's: templates an earlier build or display made.
   */
  fun prune() {
    val current = scopeDir()
    root.listFiles()?.filter { it != current }?.forEach { it.deleteRecursively() }
  }

  companion object {
    /**
     * An app's store (the phone's and the watch's alike): under `files/terrazzo/designs/local`,
     * scoped to this install's update time (which changes with every build installed) and the
     * display's density and font scale.
     */
    fun of(context: Context): FileLocalDesignStore {
      val installed by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }
          .getOrDefault(0L)
      }
      return FileLocalDesignStore(File(context.filesDir, "terrazzo/designs/local")) {
        val configuration = context.resources.configuration
        "$installed-${configuration.densityDpi}-${configuration.fontScale}"
      }
    }
  }
}
