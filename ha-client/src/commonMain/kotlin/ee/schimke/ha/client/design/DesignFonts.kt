package ee.schimke.ha.client.design

import ee.schimke.ha.client.platformHttpClient
import ee.schimke.ha.design.CardFontManifest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** Where a device keeps the fonts its design server serves: the manifest and each face by hash. */
interface FontStore {
  suspend fun readManifest(): String?

  suspend fun writeManifest(manifest: String)

  suspend fun readFace(sha256: String): ByteArray?

  suspend fun writeFace(sha256: String, bytes: ByteArray)
}

/** A face a card can name, from the store: its [family] name, [weight], slant and [bytes]. */
class CachedFontFace(
  val family: String,
  val weight: Int,
  val italic: Boolean,
  val sha256: String,
  val bytes: ByteArray,
)

/**
 * The fonts card designs name, fetched from the design server beside its designs
 * ([CardFontManifest]) and kept in a [FontStore], so a device plays them offline as it does the
 * designs. Faces are content-addressed: one that doesn't hash to the manifest's SHA-256 is never
 * stored, and one already stored is never fetched again.
 */
object DesignFonts {
  /**
   * Fetch [baseUrl]'s font manifest and every face [store] doesn't already hold. Returns whether
   * the store's fonts changed. A failure keeps what was stored: fonts only ever add to what a
   * device can draw.
   */
  suspend fun refresh(baseUrl: String, client: HttpClient, store: FontStore): Boolean {
    val base = baseUrl.trimEnd('/')
    val response = runCatching { client.get("$base/${CardFontManifest.PATH}") }.getOrNull()
    if (response == null || !response.status.isSuccess()) return false
    val text = response.bodyAsText()
    val manifest = runCatching { CardFontManifest.decode(text) }.getOrNull() ?: return false
    var changed = false
    for ((_, face) in manifest.faces) {
      val sha = face.sha256?.takeIf { SHA256.matches(it) } ?: continue
      if (!CardFontManifest.FILE_NAME.matches(face.file) || store.readFace(sha) != null) continue
      val bytes =
        runCatching { client.get("$base/${CardFontManifest.facePath(face.file)}") }
          .getOrNull()
          ?.takeIf { it.status.isSuccess() }
          ?.bodyAsBytes() ?: continue
      if (Sha256.hex(bytes) != sha) continue
      store.writeFace(sha, bytes)
      changed = true
    }
    if (store.readManifest() != text) {
      store.writeManifest(text)
      changed = true
    }
    return changed
  }

  /** As [refresh], with the platform's own HTTP client, closed when done. */
  suspend fun refresh(baseUrl: String, store: FontStore): Boolean {
    val client = platformHttpClient()
    return try {
      refresh(baseUrl, client, store)
    } finally {
      client.close()
    }
  }

  /** Every face [store] holds for its stored manifest, verified; a missing face is left out. */
  suspend fun cached(store: FontStore): List<CachedFontFace> {
    val manifest =
      store.readManifest()?.let { runCatching { CardFontManifest.decode(it) }.getOrNull() }
        ?: return emptyList()
    return manifest.faces.mapNotNull { (family, face) ->
      val sha = face.sha256 ?: return@mapNotNull null
      val bytes = store.readFace(sha)?.takeIf { Sha256.hex(it) == sha } ?: return@mapNotNull null
      CachedFontFace(family, face.weight, face.italic, sha, bytes)
    }
  }

  private val SHA256 = Regex("[0-9a-f]{64}")
}
