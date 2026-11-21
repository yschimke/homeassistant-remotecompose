package ee.schimke.ha.design

// Where a device keeps the faces its designs name. `ha-client`'s DesignFonts fills a FontStore
// from a design server; an app can also bundle faces and hand them to the renderer directly.

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

/** A [FontStore] in memory, for a host that fetches its fonts on each start. */
class InMemoryFontStore : FontStore {
  private var manifest: String? = null
  private val faces = mutableMapOf<String, ByteArray>()

  override suspend fun readManifest(): String? = manifest

  override suspend fun writeManifest(manifest: String) {
    this.manifest = manifest
  }

  override suspend fun readFace(sha256: String): ByteArray? = faces[sha256]

  override suspend fun writeFace(sha256: String, bytes: ByteArray) {
    faces[sha256] = bytes
  }
}
