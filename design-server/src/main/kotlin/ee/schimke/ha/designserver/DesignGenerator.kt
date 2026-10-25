package ee.schimke.ha.designserver

import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.designs.CheckedInCardDesigns
import ee.schimke.ha.designs.LibraryCaptureProfile
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CommonCaptureHost
import ee.schimke.ha.rc.cards.defaultRegistry
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Card designs by key: the library's, or, for a key the library doesn't hold, captured here on
 * request. The server runs the app's own converters and card designs on the JVM, with rc-players'
 * Compose Multiplatform creation fork under the app's operations ([LibraryCaptureProfile]): what
 * `:card-designs:generateCardDesignsJvm` does for the whole library, one key at a time, and what
 * `checkCardDesignParity` shows draws as the app's Android capture does.
 *
 * It captures the mobile-app surface at the capture profile's API level, as the library does; the
 * launcher and Wear surfaces still capture on Android. A captured design is kept, by key, for as
 * long as the library it was captured beside: a library reload can change the checked-in designs
 * ([DesignLibrary.sources]) a capture draws from. At most [maxGenerated] are kept, the oldest
 * dropped first.
 */
class DesignGenerator(
  private val holder: LibraryHolder,
  private val maxGenerated: Int = 4096,
) {
  /**
   * One capture at a time: each is a composition, and a burst of them shouldn't starve requests.
   */
  private val captures = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
  private val host = CommonCaptureHost(LibraryCaptureProfile.OPERATIONS)
  private val registry = defaultRegistry()

  private var generatedFor: DesignLibrary? = null
  private val generated = LinkedHashMap<DesignKey, BuiltDesign>()
  private val blobs = HashMap<String, ByteArray>()
  // How many cached keys serve each blob: keys can capture the same bytes (a variant the template
  // doesn't draw), so a blob stays until the last of them is evicted.
  private val blobKeys = HashMap<String, Int>()

  /**
   * The design for [key]: the library's when it holds one, otherwise captured now. Throws
   * [UnsupportedKey] for a key this server can't capture.
   */
  suspend fun design(key: DesignKey): GeneratedDesign {
    val library = holder.current()
    library.designFor(key)?.let {
      return GeneratedDesign(
        sha256 = it.entry.sha256,
        blob = blobPath(it.entry.sha256),
        key = key,
        slots = it.entry.slots,
        generated = false,
      )
    }
    cached(library, key)?.let {
      return it.served(generated = true)
    }
    // The registry falls back to a placeholder for any type it doesn't know, which would capture a
    // design for whatever a caller names: only types with a converter of their own are captured.
    if (registry.get(key.cardType) == null) {
      throw UnsupportedKey("no converter for card type ${key.cardType}")
    }
    if (key.surface != DesignSurface.MobileApp) {
      throw UnsupportedKey("only mobile-app designs are captured here, not ${key.surface.wire}")
    }
    if (key.rcApiLevel != CardDesignTemplates.MOBILE_APP_API_LEVEL) {
      throw UnsupportedKey(
        "mobile-app designs are captured at rcApiLevel ${CardDesignTemplates.MOBILE_APP_API_LEVEL}"
      )
    }
    if (key.schema != DesignKey.BINDING_SCHEMA_VERSION) {
      throw UnsupportedKey("binding schema ${DesignKey.BINDING_SCHEMA_VERSION} only")
    }
    val built = withContext(captures) { cached(library, key) ?: capture(library, key) }
    return built.served(generated = true)
  }

  /** A captured design's bytes, or null when [sha256] isn't one this server captured. */
  fun blob(sha256: String): ByteArray? = synchronized(this) { blobs[sha256] }

  /** [design]'s bytes, wherever they are held. */
  fun bytes(design: GeneratedDesign): ByteArray? =
    if (design.generated) blob(design.sha256) else holder.current().blob(design.sha256)

  private fun cached(library: DesignLibrary, key: DesignKey): BuiltDesign? =
    synchronized(this) {
      if (generatedFor !== library) {
        generatedFor = library
        generated.clear()
        blobs.clear()
        blobKeys.clear()
      }
      generated[key]
    }

  private fun capture(library: DesignLibrary, key: DesignKey): BuiltDesign {
    // The library's own checked-in design for the card type, as `CardDesignLibrary` captures it.
    val source = library.sources[key.cardType]?.let { CardDesignDocument.parse(it.toString()) }
    val built =
      try {
        if (source != null) CheckedInCardDesigns.captureDesign(host, key, source)
        else CardDesignTemplates.capture(host, key)
      } catch (e: IllegalArgumentException) {
        // An unknown card type, a shape its converter has no template for, an unknown theme.
        throw UnsupportedKey(e.message ?: "no design for $key")
      }
    synchronized(this) {
      if (generatedFor === library) {
        generated[key] = built
        val sha = Sha256.hex(built.bytes)
        blobs[sha] = built.bytes
        blobKeys.merge(sha, 1, Int::plus)
        while (generated.size > maxGenerated) {
          val oldest = generated.keys.first()
          val evicted = Sha256.hex(generated.remove(oldest)!!.bytes)
          if (blobKeys.merge(evicted, -1, Int::plus) == 0) {
            blobKeys.remove(evicted)
            blobs.remove(evicted)
          }
        }
      }
    }
    return built
  }

  private fun BuiltDesign.served(generated: Boolean): GeneratedDesign {
    val sha = Sha256.hex(bytes)
    return GeneratedDesign(sha, blobPath(sha), key, slots, generated)
  }

  private fun blobPath(sha256: String) = "$API_BASE/blobs/$sha256.rc"

  /** A key this server can't capture a design for. */
  class UnsupportedKey(message: String) : Exception(message)
}

/** A design by key: `POST /designs/v1/generate`. */
@Serializable
data class GeneratedDesign(
  val sha256: String,
  /** Where its `.rc` is served. */
  val blob: String,
  val key: DesignKey,
  /** Slot count the template binds (`<domain>.s0` … `<domain>.s<n-1>`). */
  val slots: Int,
  /** True when captured on request rather than taken from the library. */
  val generated: Boolean,
)
