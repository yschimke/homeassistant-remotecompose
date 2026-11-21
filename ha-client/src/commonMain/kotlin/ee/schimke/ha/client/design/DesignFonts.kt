package ee.schimke.ha.client.design

import ee.schimke.ha.client.HaHttpClientFactory
import ee.schimke.ha.design.CachedFontFace
import ee.schimke.ha.design.CardFontManifest
import ee.schimke.ha.design.FontStore
import ee.schimke.ha.design.Sha256
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * The fonts card designs name, fetched from the design server beside its designs
 * ([CardFontManifest]) and kept in a [FontStore], so a device plays them offline as it does the
 * designs. Faces are content-addressed: one that doesn't hash to the manifest's SHA-256 is never
 * stored, and one already stored is never fetched again.
 */
object DesignFonts {
  /**
   * Fetch [baseUrl]'s font manifest and every face [store] doesn't already hold. Returns whether
   * the store's fonts changed. A failure keeps what was stored: a stored manifest is replaced only
   * when every face the new one names has been fetched and verified. [token] is the bearer for
   * [baseUrl], as for its designs ([DesignServerConfig.token]); blank sends none.
   */
  suspend fun refresh(
    baseUrl: String,
    client: HttpClient,
    store: FontStore,
    token: suspend () -> String = { "" },
  ): Boolean {
    val base = baseUrl.trimEnd('/')
    val bearer: suspend HttpRequestBuilder.() -> Unit = {
      token().takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }
    val response = runCatching {
      client.get("$base/${CardFontManifest.PATH}") { bearer() }
    }
      .getOrNull()
    if (response == null || !response.status.isSuccess()) return false
    val text = response.bodyAsText()
    val manifest = runCatching { CardFontManifest.decode(text) }.getOrNull() ?: return false
    var changed = false
    var complete = true
    for ((_, face) in manifest.faces) {
      val sha = face.sha256?.takeIf { SHA256.matches(it) } ?: continue
      if (!CardFontManifest.FILE_NAME.matches(face.file) || store.readFace(sha) != null) continue
      val bytes = runCatching {
        // Versioned by its hash, so a face replaced under its name never comes from a cache.
        client.get("$base/${CardFontManifest.facePath(face.file)}?v=$sha") { bearer() }
      }
        .getOrNull()
        ?.takeIf { it.status.isSuccess() }
        ?.bodyAsBytes()
      if (bytes == null || Sha256.hex(bytes) != sha) {
        complete = false
        continue
      }
      store.writeFace(sha, bytes)
      changed = true
    }
    // The stored manifest names the faces a device draws with: it moves to the new one only once
    // every face that names is stored, so a replacement that failed keeps the face it replaces.
    // With none stored yet, whatever did arrive beats nothing.
    val stored = store.readManifest()
    if ((complete || stored == null) && stored != text) {
      store.writeManifest(text)
      changed = true
    }
    return changed
  }

  /** As [refresh], with the platform's own HTTP client, closed when done. */
  suspend fun refresh(
    baseUrl: String,
    store: FontStore,
    token: suspend () -> String = { "" },
    httpClients: HaHttpClientFactory = HaHttpClientFactory.Platform,
  ): Boolean {
    val client = httpClients.create {}
    return try {
      refresh(baseUrl, client, store, token)
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

  /** The public design server, whose fonts a host without a server of its own draws with. */
  const val PUBLIC_SERVER: String = "https://ha.coo.ee/designs/v1"

  private val SHA256 = Regex("[0-9a-f]{64}")
}
