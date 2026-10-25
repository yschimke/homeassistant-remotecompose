package ee.schimke.terrazzo.shared

import java.io.File
import java.util.Properties

/** The desktop app keeps its settings in `~/.terrazzo/settings.properties`. */
internal actual fun appSettings(): AppStorage? =
  FileAppStorage(File(System.getProperty("user.home"), ".terrazzo/settings.properties"))

/** Settings in one properties file, read once and rewritten on each change. */
internal class FileAppStorage(private val file: File) : AppStorage {
  override val location = StorageLocation.DEVICE

  private val values = Properties().apply { runCatching { file.inputStream().use { load(it) } } }

  override fun get(key: String): String? = synchronized(values) { values.getProperty(key) }

  override fun set(key: String, value: String?) {
    synchronized(values) {
      if (value == null) values.remove(key) else values.setProperty(key, value)
      // An unwritable home directory keeps the setting for this session only.
      runCatching {
        file.parentFile?.mkdirs()
        file.outputStream().use { values.store(it, null) }
      }
    }
  }
}
