package ee.schimke.ha.client.design

import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Client configuration for the card design server.
 *
 * @property baseUrl where `manifest.json` and `blobs/` live, or null to switch the remote source
 *   off. Off is the default: with no server, [DesignRepository] only serves cached or inbuilt
 *   designs (and in the spike there are none), so every card renders through the local converter
 *   exactly as before.
 * @property allowUnsigned accept manifests without a signature. Spike / debug only — manifest
 *   signing is not implemented yet.
 */
data class DesignServerConfig(
  val baseUrl: String? = null,
  val allowUnsigned: Boolean = false,
  val clientVersion: Int = 0,
) {
  val enabled: Boolean
    get() = !baseUrl.isNullOrBlank()

  companion object {
    val Off: DesignServerConfig = DesignServerConfig()
  }
}

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
}

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

/** Outcome of a [DesignRepository.refresh] — for logs and tests. */
enum class RefreshResult {
  Disabled,
  NotDue,
  Updated,
  NotModified,
  Rejected,
  Failed,
}

/**
 * Card design cache with refresh rules and fallback.
 *
 * Resolution order for a [DesignKey]: a cached remote design (fresh or stale, unless revoked) → an
 * [InbuiltDesigns] design → null, which tells the caller to use the local converter. A refresh
 * never blocks resolution; callers render from what is on disk and pick up new designs on the next
 * capture.
 */
class DesignRepository(
  private val config: DesignServerConfig,
  private val store: DesignStore,
  private val httpClient: HttpClient?,
  private val inbuilt: InbuiltDesigns = InbuiltDesigns.None,
  private val clock: () -> Long,
) {
  private val refreshLock = Mutex()
  private var lastFailureAtMs: Long? = null
  private var failures = 0

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Fetch the manifest if it is past its TTL (or [force]), then download any blobs it lists that
   * aren't cached yet. Never throws for network or server trouble; the previous cache stays.
   */
  suspend fun refresh(force: Boolean = false): RefreshResult = refreshLock.withLock {
    val baseUrl = config.baseUrl?.trimEnd('/')
    if (!config.enabled || baseUrl == null || httpClient == null) return RefreshResult.Disabled
    val now = clock()
    val stored = store.readManifest()
    if (!force && stored != null && !isPastTtl(stored, now)) return RefreshResult.NotDue
    if (!force && inBackoff(now)) return RefreshResult.NotDue

    val result = runCatching { fetchManifest(baseUrl, stored, now) }.getOrElse { Fetch.Failed }
    when (result) {
      Fetch.Failed -> {
        recordFailure(now)
        RefreshResult.Failed
      }
      Fetch.NotModified -> {
        stored?.let { store.writeManifest(it.copy(fetchedAtEpochMs = now)) }
        recordSuccess()
        RefreshResult.NotModified
      }
      is Fetch.Body -> {
        val manifest = result.manifest
        val highest = stored?.highestSerial ?: Long.MIN_VALUE
        val acceptable =
          manifest.serial >= highest &&
            manifest.expiresAtEpochMs > now &&
            manifest.minClient <= config.clientVersion &&
            (manifest.signature != null || config.allowUnsigned)
        // TODO(design-server): verify manifest.signature (Ed25519) once signing lands.
        if (!acceptable) {
          recordFailure(now)
          return RefreshResult.Rejected
        }
        for (entry in manifest.designs) {
          if (entry.revoked || store.readBlob(entry.sha256) != null) continue
          val bytes = runCatching { fetchBlob(baseUrl, entry) }.getOrNull() ?: continue
          store.writeBlob(entry.sha256, bytes)
        }
        // Only switch to the new manifest once every design it lists is on disk. Otherwise keep
        // the previous one, whose verified blobs still serve as the stale fallback.
        if (manifest.designs.any { !it.revoked && store.readBlob(it.sha256) == null }) {
          recordFailure(now)
          return RefreshResult.Failed
        }
        store.writeManifest(
          StoredManifest(
            manifest = manifest,
            etag = result.etag,
            fetchedAtEpochMs = now,
            highestSerial = maxOf(highest, manifest.serial),
          )
        )
        recordSuccess()
        RefreshResult.Updated
      }
    }
  }

  /** Best design for [key], or null to fall through to the local converter. */
  suspend fun resolve(key: DesignKey): DesignBlob? {
    val stored = store.readManifest()
    if (stored != null) {
      val entry = stored.manifest.designs.firstOrNull { it.key == key && !it.revoked }
      if (entry != null) {
        val bytes = store.readBlob(entry.sha256)
        if (bytes != null && Sha256.hex(bytes) == entry.sha256) {
          val kind =
            if (isPastTtl(stored, clock())) DesignSourceKind.Stale else DesignSourceKind.Fresh
          return DesignBlob(entry, bytes, kind)
        }
      }
      // A revoked entry also blocks the inbuilt copy of the same key.
      if (stored.manifest.designs.any { it.key == key && it.revoked }) return null
    }
    return inbuilt.find(key)?.let { (entry, bytes) ->
      DesignBlob(entry, bytes, DesignSourceKind.Inbuilt)
    }
  }

  private fun isPastTtl(stored: StoredManifest, now: Long): Boolean =
    now - stored.fetchedAtEpochMs >= stored.manifest.ttlSeconds * 1000 ||
      now >= stored.manifest.expiresAtEpochMs

  private fun inBackoff(now: Long): Boolean {
    val last = lastFailureAtMs ?: return false
    val backoffMs =
      when (failures) {
        1 -> HOUR_MS
        2 -> 4 * HOUR_MS
        else -> 24 * HOUR_MS
      }
    return now - last < backoffMs
  }

  private fun recordFailure(now: Long) {
    failures++
    lastFailureAtMs = now
  }

  private fun recordSuccess() {
    failures = 0
    lastFailureAtMs = null
  }

  private sealed interface Fetch {
    data object Failed : Fetch

    data object NotModified : Fetch

    class Body(val manifest: DesignManifest, val etag: String?) : Fetch
  }

  private suspend fun fetchManifest(baseUrl: String, stored: StoredManifest?, now: Long): Fetch {
    val response: HttpResponse =
      httpClient!!.get("$baseUrl/manifest.json") {
        stored?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
      }
    if (response.status == HttpStatusCode.NotModified) {
      return if (stored != null && stored.manifest.expiresAtEpochMs > now) Fetch.NotModified
      else Fetch.Failed
    }
    if (!response.status.isSuccess()) return Fetch.Failed
    val manifest = json.decodeFromString(DesignManifest.serializer(), response.bodyAsText())
    return Fetch.Body(manifest, response.headers[HttpHeaders.ETag])
  }

  private suspend fun fetchBlob(baseUrl: String, entry: DesignEntry): ByteArray? {
    val response = httpClient!!.get("$baseUrl/${DesignManifest.blobPath(entry.sha256)}")
    if (!response.status.isSuccess()) return null
    val bytes = response.bodyAsBytes()
    // Content addressing: a blob that doesn't hash to its name is never stored.
    return bytes.takeIf { it.size == entry.bytes && Sha256.hex(it) == entry.sha256 }
  }

  private companion object {
    const val HOUR_MS: Long = 60 * 60 * 1000
  }
}

/** A built design before publishing: its key, slot count and template bytes. */
class BuiltDesign(val key: DesignKey, val slots: Int, val bytes: ByteArray)

/**
 * Produces the files a static design host serves: `manifest.json` and `blobs/<sha256>.rc`. Shared
 * by the template pipeline and tests, so both write exactly what the client reads.
 */
object DesignPublisher {
  private val json = Json { prettyPrint = true }

  class Output(val manifest: DesignManifest, val files: Map<String, ByteArray>)

  fun publish(
    designs: List<BuiltDesign>,
    serial: Long,
    nowEpochMs: Long,
    validForMs: Long = 14L * 24 * 60 * 60 * 1000,
  ): Output {
    val files = LinkedHashMap<String, ByteArray>()
    val entries = designs.map { design ->
      val sha = Sha256.hex(design.bytes)
      files[DesignManifest.blobPath(sha)] = design.bytes
      DesignEntry(key = design.key, sha256 = sha, bytes = design.bytes.size, slots = design.slots)
    }
    val manifest =
      DesignManifest(
        serial = serial,
        generatedAtEpochMs = nowEpochMs,
        expiresAtEpochMs = nowEpochMs + validForMs,
        designs = entries,
      )
    files["manifest.json"] =
      json.encodeToString(DesignManifest.serializer(), manifest).encodeToByteArray()
    return Output(manifest, files)
  }
}

/**
 * A [DesignRepository] with the platform's default HTTP engine, or no client at all when [config]
 * is off — so an app with no design server configured never opens a connection.
 */
fun createDesignRepository(
  config: DesignServerConfig,
  store: DesignStore,
  inbuilt: InbuiltDesigns = InbuiltDesigns.None,
  clock: () -> Long,
): DesignRepository =
  DesignRepository(config, store, if (config.enabled) HttpClient() else null, inbuilt, clock)
