package ee.schimke.ha.client.design.glyphs

import ee.schimke.ha.client.design.Sha256
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [GlyphStore] in a directory: one file a face and size, named by its key's SHA-256 so any family
 * name makes a file name. A write lands whole (written beside it, then renamed), so a pack read
 * after a crash is the last one written or none.
 */
class DirectoryGlyphStore(private val directory: File) : GlyphStore {
  private fun file(key: String) = File(directory, Sha256.hex(key.encodeToByteArray()) + ".rcgp")

  override suspend fun read(key: String): ByteArray? =
    withContext(Dispatchers.IO) { file(key).takeIf { it.isFile }?.readBytes() }

  override suspend fun write(key: String, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
      directory.mkdirs()
      val target = file(key)
      val partial = File(directory, target.name + ".partial")
      partial.writeBytes(bytes)
      if (!partial.renameTo(target)) {
        target.delete()
        partial.renameTo(target)
      }
    }
  }
}
