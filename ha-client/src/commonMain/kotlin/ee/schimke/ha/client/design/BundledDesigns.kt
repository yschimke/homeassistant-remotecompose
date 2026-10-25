package ee.schimke.ha.client.design

import ee.schimke.ha.design.DesignManifest
import kotlinx.serialization.json.Json

/**
 * Designs an app ships with, in a design server's layout (`manifest.json`, `blobs/<sha256>.rc`),
 * for a design cache that has none yet. Seeding writes them into the cache as if a sync had fetched
 * them, so everything that reads the cache (resolution, the dashboard's preload) draws them with no
 * design server at all. The bundled manifest's serial is 0 and it is already past its expiry, so
 * the first sync that reaches a server replaces it, whatever that server's serial.
 */
object BundledDesigns {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Fills [store] from the bundle [open] reads (a path within it, `manifest.json` or `blobs/…`;
   * null when it has no such file) when [store] holds no manifest yet. Blobs first, then the
   * manifest, so a reader never sees an entry before its blob; a blob that doesn't hash to its name
   * is left out (its entry then resolves to nothing). Never throws: a missing or unreadable bundle
   * seeds nothing. True when it seeded.
   */
  suspend fun seed(store: DesignStore, open: (path: String) -> ByteArray?): Boolean {
    if (store.readManifest() != null) return false
    val manifest =
      runCatching {
        open(MANIFEST)?.let {
          json.decodeFromString(DesignManifest.serializer(), it.decodeToString())
        }
      }
        .getOrNull() ?: return false
    for (sha in manifest.designs.map { it.sha256 }.distinct()) {
      if (store.readBlob(sha) != null) continue
      val bytes =
        runCatching { open(DesignManifest.blobPath(sha)) }
          .getOrNull()
          ?.takeIf { Sha256.hex(it) == sha } ?: continue
      store.writeBlob(sha, bytes)
    }
    // A sync may have stored a server's manifest meanwhile: that one wins.
    if (store.readManifest() != null) return false
    store.writeManifest(StoredManifest(manifest, etag = null, fetchedAtEpochMs = 0))
    return true
  }

  private const val MANIFEST = "manifest.json"
}
