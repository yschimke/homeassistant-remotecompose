package ee.schimke.terrazzo.dashboard

import android.content.Context
import ee.schimke.ha.client.design.DesignFonts
import ee.schimke.ha.client.design.FontStore
import ee.schimke.ha.rc.HaFontLibrary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The fonts card designs name, kept beside the designs of the server they came from
 * ([CardDesignSync.storeDir]`/fonts`): fetched with the design sync ([refresh]) and installed for
 * every player ([HaFontLibrary]) from the cache, so a card plays them offline as it does its
 * design.
 */
object CardFonts {
  private fun store(context: Context, serverUrl: String): FontStore =
    FileFontStore(File(CardDesignSync.storeDir(context, serverUrl), "fonts"))

  /** Install the fonts cached for [serverUrl]; none when no server is configured. */
  suspend fun load(context: Context, serverUrl: String) {
    val faces =
      if (serverUrl.isBlank()) emptyList()
      else withContext(Dispatchers.IO) { DesignFonts.cached(store(context, serverUrl)) }
    HaFontLibrary.install(faces)
  }

  /**
   * Fetch [choice]'s server's fonts the cache lacks, with the bearer its designs use, and install
   * them when anything changed.
   */
  suspend fun refresh(context: Context, choice: CardDesignChoice) {
    val serverUrl = choice.serverUrl
    if (serverUrl.isBlank()) return
    val changed = runCatching {
      DesignFonts.refresh(
        serverUrl,
        store(context, serverUrl),
        CardDesignSync.tokenFor(context, choice),
      )
    }
      .getOrDefault(false)
    if (changed) load(context, serverUrl)
  }
}

/** A [FontStore] in [dir]: the manifest as `fonts.json`, each face as `<sha256>.font`. */
private class FileFontStore(private val dir: File) : FontStore {
  private val manifest = File(dir, "fonts.json")

  override suspend fun readManifest(): String? =
    withContext(Dispatchers.IO) { manifest.takeIf { it.isFile }?.readText() }

  override suspend fun writeManifest(manifest: String) =
    withContext(Dispatchers.IO) { write(this@FileFontStore.manifest, manifest.encodeToByteArray()) }

  override suspend fun readFace(sha256: String): ByteArray? =
    withContext(Dispatchers.IO) { face(sha256).takeIf { it.isFile }?.readBytes() }

  override suspend fun writeFace(sha256: String, bytes: ByteArray) =
    withContext(Dispatchers.IO) { write(face(sha256), bytes) }

  private fun face(sha256: String) = File(dir, "$sha256.font")

  /** Written beside and renamed into place, so a reader never sees half a file. */
  private fun write(file: File, bytes: ByteArray) {
    dir.mkdirs()
    val partial = File(dir, "${file.name}.partial")
    partial.writeBytes(bytes)
    if (!partial.renameTo(file)) {
      file.delete()
      partial.renameTo(file)
    }
  }
}
