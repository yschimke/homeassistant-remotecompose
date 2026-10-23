package ee.schimke.terrazzo.dashboard

import android.content.Context
import ee.schimke.ha.design.DesignEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The skins installed on this device: `files/terrazzo/designs/skins.json`. A skin is a card design
 * from the design server, named by its stable id ([DesignEntry.designId]). The design sync keeps an
 * installed skin's assets for every theme the dashboard draws in; a skin that isn't installed is
 * only listed (the manifest names it), never downloaded, and a card switched to it draws as if it
 * weren't. The library's skins ([DesignEntry.libraryDesignId]) come with every sync, so they are
 * always installed. Writes replace the file atomically.
 */
class SkinStore internal constructor(private val file: File) {
  private val _installed = MutableStateFlow(read())

  /** One update and its write at a time, so the file always ends up with the newest set. */
  private val writes = Mutex()

  /** The installed skins' ids, library skins aside. */
  val installed: StateFlow<Set<String>> = _installed.asStateFlow()

  /** Records [designId] as installed, once its assets are on disk ([Skins.install]). */
  suspend fun add(designId: String) = update { it + designId }

  /** Forgets [designId]: the sync stops fetching it, and cards switched to it draw as before. */
  suspend fun remove(designId: String) = update { it - designId }

  private suspend fun update(change: (Set<String>) -> Set<String>) {
    writes.withLock {
      _installed.update(change)
      val text = json.encodeToString(Installed.serializer(), Installed(_installed.value.sorted()))
      withContext(Dispatchers.IO) { write(text) }
    }
  }

  private fun read(): Set<String> = runCatching {
    file
      .takeIf { it.isFile }
      ?.readText()
      ?.let { json.decodeFromString(Installed.serializer(), it).installed.toSet() }
  }
    .getOrNull()
    .orEmpty()

  private fun write(text: String) {
    val dir = requireNotNull(file.parentFile).apply { mkdirs() }
    val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
    try {
      tmp.writeText(text)
      java.nio.file.Files.move(
        tmp.toPath(),
        file.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      tmp.delete()
    }
  }

  @Serializable private data class Installed(val installed: List<String> = emptyList())

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var instance: SkinStore? = null

    /** The app's one store. */
    fun of(context: Context): SkinStore =
      instance
        ?: synchronized(this) {
          instance
            ?: SkinStore(File(context.applicationContext.filesDir, "terrazzo/designs/skins.json"))
              .also { instance = it }
        }
  }
}
