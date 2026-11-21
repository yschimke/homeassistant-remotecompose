package ee.schimke.ha.design

// The network-free half of the design library: where a device keeps designs and what a resolved
// or built design is. `ha-client`'s DesignRepository fills a DesignStore from a design server;
// an app that ships its own designs fills one itself (BundledDesigns) and never needs Ktor.

/** A resolved design: the manifest entry plus its verified bytes. */
class DesignBlob(val entry: DesignEntry, val bytes: ByteArray, val source: DesignSourceKind)

/** Where a resolved design came from. Telemetry / debug only; UI must not branch on it. */
enum class DesignSourceKind {
  /** Cache filled by a refresh that is still within its TTL. */
  Fresh,
  /** Cache past its TTL or from a failed refresh — the stale fallback. */
  Stale,
  /** Shipped with the app. */
  Inbuilt,
}

/**
 * Storage for the last good manifest and its blobs. Content-addressed and not per HA instance:
 * designs hold no user data. Implementations must write atomically.
 */
interface DesignStore {
  suspend fun readManifest(): StoredManifest?

  suspend fun writeManifest(manifest: StoredManifest)

  suspend fun readBlob(sha256: String): ByteArray?

  suspend fun writeBlob(sha256: String, bytes: ByteArray)

  /** The designs fetched by key ([DesignRepository.generate]), or null when none are stored. */
  suspend fun readGenerated(): GeneratedDesigns?

  suspend fun writeGenerated(designs: GeneratedDesigns)
}

/**
 * Designs fetched by key ([DesignRepository.generate]) for keys the manifest doesn't list, under
 * the manifest [serial] they were fetched beside: a new manifest (the server's library changed)
 * drops them, and they are fetched again as cards need them.
 */
@kotlinx.serialization.Serializable
data class GeneratedDesigns(val serial: Long, val designs: List<DesignEntry>)

/** The manifest plus the client-side bookkeeping refresh rules need. */
@kotlinx.serialization.Serializable
data class StoredManifest(
  val manifest: DesignManifest,
  val etag: String? = null,
  val fetchedAtEpochMs: Long,
  /** Highest serial ever accepted — rollback protection survives a manifest being replaced. */
  val highestSerial: Long = manifest.serial,
)

class InMemoryDesignStore : DesignStore {
  private var manifest: StoredManifest? = null
  private val blobs = HashMap<String, ByteArray>()
  private var generated: GeneratedDesigns? = null

  override suspend fun readGenerated(): GeneratedDesigns? = generated

  override suspend fun writeGenerated(designs: GeneratedDesigns) {
    generated = designs
  }

  override suspend fun readManifest(): StoredManifest? = manifest

  override suspend fun writeManifest(manifest: StoredManifest) {
    this.manifest = manifest
  }

  override suspend fun readBlob(sha256: String): ByteArray? = blobs[sha256]

  override suspend fun writeBlob(sha256: String, bytes: ByteArray) {
    blobs[sha256] = bytes
  }
}

/** Designs bundled with the app — the last fallback before the local converter. */
fun interface InbuiltDesigns {
  fun find(key: DesignKey): Pair<DesignEntry, ByteArray>?

  companion object {
    /** The spike ships no inbuilt designs. */
    val None: InbuiltDesigns = InbuiltDesigns { null }
  }
}

/** A built design before publishing: its key, slot count and template bytes. */
class BuiltDesign(val key: DesignKey, val slots: Int, val bytes: ByteArray)
