package ee.schimke.ha.client.design

import ee.schimke.ha.client.platformHttpClient
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignQuery
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.design.DeviceMatch
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
 * @property choice the designs this client keeps, by tag: a refresh downloads only the blobs of
 *   manifest entries one of these matches. Empty keeps every design. The whole manifest is always
 *   fetched, so the server learns nothing about the choice beyond the blobs it implies.
 */
data class DesignServerConfig(
  val baseUrl: String? = null,
  val allowUnsigned: Boolean = false,
  val clientVersion: Int = 0,
  val choice: List<DesignQuery> = emptyList(),
  /**
   * A device token from the server's `/my` page, sent as `Authorization: Bearer` with the manifest
   * request so it lists the signed-in person's private designs too. Blank for none.
   */
  val token: String = "",
  /**
   * The fixed User-Agent sent with every manifest and blob request ([UsageUserAgent.header]:
   * `Terrazzo/<version> (<platform>; surface=<surface>)`), so the server can count which templates
   * each app fetches. It names a release, never an install; null sends the HTTP engine's own.
   */
  val userAgent: UsageUserAgent? = null,
) {
  val enabled: Boolean
    get() = !baseUrl.isNullOrBlank()

  /** Whether [entry] is one of the designs this client keeps. */
  fun wants(entry: DesignEntry): Boolean = choice.isEmpty() || choice.any { it.matches(entry) }

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
  /** The manifest wasn't due, but blobs the [DesignServerConfig.choice] needs were downloaded. */
  Filled,
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
   * Fetch the manifest if it is past its TTL (or [force]), then download the blobs of the designs
   * [DesignServerConfig.choice] keeps that aren't cached yet. While the manifest isn't due, only
   * fill in chosen blobs the cache lacks (a choice that changed since the last refresh). Never
   * throws for network or server trouble; the previous cache stays.
   */
  suspend fun refresh(force: Boolean = false): RefreshResult = refreshLock.withLock {
    val baseUrl = config.baseUrl?.trimEnd('/')
    if (!config.enabled || baseUrl == null || httpClient == null) return RefreshResult.Disabled
    val now = clock()
    val stored = store.readManifest()
    if (!force && inBackoff(now)) return RefreshResult.NotDue
    if (!force && stored != null && !isPastTtl(stored, now)) {
      return if (fetchMissing(baseUrl, stored.manifest) > 0) RefreshResult.Filled
      else RefreshResult.NotDue
    }

    val result = runCatching { fetchManifest(baseUrl, stored, now) }.getOrElse { Fetch.Failed }
    when (result) {
      Fetch.Failed -> {
        recordFailure(now)
        RefreshResult.Failed
      }
      Fetch.NotModified -> {
        stored?.let { store.writeManifest(it.copy(fetchedAtEpochMs = now)) }
        recordSuccess()
        // The manifest is unchanged, but the choice may not be: fill in what it now needs.
        val filled = stored?.let { fetchMissing(baseUrl, it.manifest) } ?: 0
        if (filled > 0) RefreshResult.Filled else RefreshResult.NotModified
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
        fetchMissing(baseUrl, manifest)
        // Only switch to the new manifest once every chosen design it lists is on disk. Otherwise
        // keep the previous one, whose verified blobs still serve as the stale fallback — but apply
        // the new manifest's revocations to it, so a kill switch never waits on an unrelated
        // download.
        if (manifest.designs.any { wanted(it) && store.readBlob(it.sha256) == null }) {
          if (stored != null) {
            // A design's slot is its key and device match: revoking a device design leaves its
            // key's generic design and device siblings alone.
            val revoked = manifest.designs.filter { it.revoked }.map { it.key to it.device }.toSet()
            if (revoked.isNotEmpty()) {
              val designs =
                stored.manifest.designs.map {
                  if ((it.key to it.device) in revoked) it.copy(revoked = true) else it
                }
              store.writeManifest(stored.copy(manifest = stored.manifest.copy(designs = designs)))
            }
          }
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

  /**
   * Every cached remote design that is not revoked and still verifies, by key. Hosts that layer
   * their own fallback underneath (a local design library) load this once and look keys up.
   *
   * [prefer] limits and orders them: only entries one of its queries matches resolve (blobs a
   * previous choice cached stay out), and between two authors' designs for one key the entry the
   * earliest query matches wins, then manifest order. With no preference every cached entry
   * resolves and the first for a key wins.
   */
  suspend fun resolveAll(prefer: List<DesignQuery> = emptyList()): Map<DesignKey, ByteArray> =
    store.readManifest()?.let { resolveAll(it, prefer) } ?: emptyMap()

  /**
   * [resolveAll] and [resolveDeviceDesigns] from one read of the manifest: decoding it is most of
   * what loading the cache costs, so a host that wants both shouldn't pay for it twice.
   */
  suspend fun resolveAllWithDevices(
    prefer: List<DesignQuery> = emptyList(),
    designIds: Set<String> = emptySet(),
  ): ResolvedDesigns {
    val stored = store.readManifest() ?: return ResolvedDesigns(emptyMap(), emptyMap())
    return ResolvedDesigns(
      resolveAll(stored, prefer),
      resolveDeviceDesigns(stored, prefer),
      resolveByDesign(stored, designIds),
    )
  }

  /**
   * Every cached entry of the designs [designIds] names, by design id, whatever its author or
   * variant: what a card switched to one design ([CardDesignOverride.designId]) plays. Unlike
   * [resolveAll] nothing competes, so no entry is collapsed away. Revoked entries and blobs that
   * aren't cached (or don't verify) are left out.
   */
  suspend fun resolveByDesign(designIds: Set<String>): DesignsById =
    store.readManifest()?.let { resolveByDesign(it, designIds) } ?: DesignsById()

  private suspend fun resolveByDesign(stored: StoredManifest, designIds: Set<String>): DesignsById {
    if (designIds.isEmpty()) return DesignsById()
    val resolved = LinkedHashMap<String, MutableList<DesignedBlob>>()
    for (entry in stored.manifest.designs) {
      val id = entry.stableDesignId ?: continue
      if (entry.revoked || id !in designIds) continue
      val bytes = verifiedBlob(entry) ?: continue
      resolved.getOrPut(id) { mutableListOf() } += DesignedBlob(entry.key, entry.device, bytes)
    }
    return DesignsById(resolved)
  }

  /**
   * The cached bytes design [designId] draws [key] with for a card on [device]: see
   * [DesignsById.find]. Null when the design has no live, cached entry for the key.
   */
  suspend fun byDesign(designId: String, key: DesignKey, device: DeviceInfo?): ByteArray? =
    resolveByDesign(setOf(designId)).find(designId, key, device)?.second

  /**
   * Every design in the cached manifest that can draw [key] for a card on [device]: see
   * [designOptions]. Read from the manifest, not the blobs, so a design whose blobs the sync hasn't
   * fetched yet is listed too; choosing it makes the next sync fetch them.
   */
  suspend fun designsFor(key: DesignKey, device: DeviceInfo?): List<DesignOption> =
    store.readManifest()?.manifest?.designs?.let { designOptions(it, key, device) }.orEmpty()

  private suspend fun verifiedBlob(entry: DesignEntry): ByteArray? =
    store.readBlob(entry.sha256)?.takeIf { Sha256.hex(it) == entry.sha256 }

  private suspend fun resolveAll(
    stored: StoredManifest,
    prefer: List<DesignQuery>,
  ): Map<DesignKey, ByteArray> {
    fun rank(entry: DesignEntry): Int =
      if (prefer.isEmpty()) 0 else prefer.indexOfFirst { it.matches(entry) }
    val resolved = LinkedHashMap<DesignKey, Pair<Int, ByteArray>>()
    for (entry in stored.manifest.designs) {
      // Device-specific designs share their generic sibling's key: see resolveDeviceDesigns.
      if (entry.revoked || entry.device != null) continue
      val rank = rank(entry)
      if (rank < 0) continue
      val current = resolved[entry.key]
      if (current != null && current.first <= rank) continue
      val bytes =
        store.readBlob(entry.sha256)?.takeIf { Sha256.hex(it) == entry.sha256 } ?: continue
      resolved[entry.key] = rank to bytes
    }
    return resolved.mapValues { it.value.second }
  }

  /**
   * The cached device-specific designs (entries with a [DesignEntry.device]) by key, the preferred
   * author's first for each key and device match, as [resolveAll] ranks generic ones. A client
   * picks among a key's candidates per card with [ee.schimke.ha.design.DeviceMatch.pick].
   */
  suspend fun resolveDeviceDesigns(
    prefer: List<DesignQuery> = emptyList()
  ): Map<DesignKey, List<Pair<DeviceMatch, ByteArray>>> =
    store.readManifest()?.let { resolveDeviceDesigns(it, prefer) } ?: emptyMap()

  private suspend fun resolveDeviceDesigns(
    stored: StoredManifest,
    prefer: List<DesignQuery>,
  ): Map<DesignKey, List<Pair<DeviceMatch, ByteArray>>> {
    fun rank(entry: DesignEntry): Int =
      if (prefer.isEmpty()) 0 else prefer.indexOfFirst { it.matches(entry) }
    val resolved = LinkedHashMap<Pair<DesignKey, DeviceMatch>, Pair<Int, ByteArray>>()
    for (entry in stored.manifest.designs) {
      val device = entry.device ?: continue
      if (entry.revoked) continue
      val rank = rank(entry)
      if (rank < 0) continue
      val slot = entry.key to device
      val current = resolved[slot]
      if (current != null && current.first <= rank) continue
      val bytes =
        store.readBlob(entry.sha256)?.takeIf { Sha256.hex(it) == entry.sha256 } ?: continue
      resolved[slot] = rank to bytes
    }
    return resolved.entries.groupBy({ it.key.first }) { it.key.second to it.value.second }
  }

  /** Whether [entry] is a live design this client keeps. */
  private fun wanted(entry: DesignEntry): Boolean = !entry.revoked && config.wants(entry)

  /** Downloads the chosen blobs of [manifest] the cache lacks; returns how many it stored. */
  private suspend fun fetchMissing(baseUrl: String, manifest: DesignManifest): Int {
    var stored = 0
    for (entry in manifest.designs.filter(::wanted).distinctBy { it.sha256 }) {
      if (store.readBlob(entry.sha256) != null) continue
      val bytes = runCatching { fetchBlob(baseUrl, entry) }.getOrNull() ?: continue
      store.writeBlob(entry.sha256, bytes)
      stored++
    }
    return stored
  }

  /** Best design for [key], or null to fall through to the local converter. */
  suspend fun resolve(key: DesignKey): DesignBlob? {
    val stored = store.readManifest()
    if (stored != null) {
      // The generic design for the key: device-specific ones resolve through resolveDeviceDesigns.
      val entry =
        stored.manifest.designs.firstOrNull { it.key == key && it.device == null && !it.revoked }
      if (entry != null) {
        val bytes = store.readBlob(entry.sha256)
        if (bytes != null && Sha256.hex(bytes) == entry.sha256) {
          val kind =
            if (isPastTtl(stored, clock())) DesignSourceKind.Stale else DesignSourceKind.Fresh
          return DesignBlob(entry, bytes, kind)
        }
      }
      // A revoked entry also blocks the inbuilt copy of the same key.
      if (stored.manifest.designs.any { it.key == key && it.device == null && it.revoked }) {
        return null
      }
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
        config.userAgent?.let { header(HttpHeaders.UserAgent, it.header) }
        stored?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        config.token
          .takeIf { it.isNotBlank() }
          ?.let { header(HttpHeaders.Authorization, "Bearer $it") }
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
    val response =
      httpClient!!.get("$baseUrl/${DesignManifest.blobPath(entry.sha256)}") {
        config.userAgent?.let { header(HttpHeaders.UserAgent, it.header) }
      }
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
 * by the template pipeline and tests, so both write exactly what the client reads. Every entry is
 * the library's: one design per card type ([DesignEntry.libraryDesignId], titled
 * [DesignEntry.LIBRARY_TITLE]).
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
      DesignEntry(
        key = design.key,
        sha256 = sha,
        bytes = design.bytes.size,
        slots = design.slots,
        designId = DesignEntry.libraryDesignId(design.key.cardType),
        title = DesignEntry.LIBRARY_TITLE,
      )
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
  DesignRepository(
    config,
    store,
    if (config.enabled) platformHttpClient() else null,
    inbuilt,
    clock,
  )

/**
 * A [DesignRepository] that only reads [store]: no HTTP client, so [DesignRepository.refresh] is
 * always [RefreshResult.Disabled]. For hosts that render designs while a background job owns the
 * network refresh.
 */
/**
 * What [DesignRepository.resolveAllWithDevices] returns: generic designs and device ones, collapsed
 * per key, and the designs asked for by id, whole.
 */
data class ResolvedDesigns(
  val byKey: Map<DesignKey, ByteArray>,
  val deviceByKey: Map<DesignKey, List<Pair<DeviceMatch, ByteArray>>>,
  val byDesign: DesignsById = DesignsById(),
)

/** One cached entry of a design: the key it draws, the devices it is for (null: any), its bytes. */
class DesignedBlob(val key: DesignKey, val device: DeviceMatch?, val bytes: ByteArray)

/** Cached designs by [DesignEntry.stableDesignId], each with every entry it has. */
class DesignsById(val entries: Map<String, List<DesignedBlob>> = emptyMap()) {
  /**
   * The key and bytes design [designId] draws [key] with for a card on [device], or null when it
   * has no entry for the key. Variants don't split a design: [key]'s own variant comes first, then
   * `default`, then any other. Within one variant the most specific entry whose device matches
   * wins, else the generic one ([DeviceMatch.pick]); an entry for another device never does.
   */
  fun find(designId: String, key: DesignKey, device: DeviceInfo?): Pair<DesignKey, ByteArray>? {
    val candidates = entries[designId].orEmpty().filter { it.key.sameExceptVariant(key) }
    if (candidates.isEmpty()) return null
    val variants =
      (listOf(key.variant, DesignKey.DEFAULT_VARIANT) + candidates.map { it.key.variant })
        .distinct()
    return variants.firstNotNullOfOrNull { variant ->
      val inVariant = candidates.filter { it.key.variant == variant }
      DeviceMatch.pick(inVariant.map { it.device to it }, device)?.let { it.key to it.bytes }
    }
  }

  /** The design ids held. */
  val ids: Set<String>
    get() = entries.keys
}

/**
 * A design a card can be switched to: [designId] with what a picker shows of it. [device] is the
 * entry's device match that fits the card (null for the generic design).
 */
data class DesignOption(
  val designId: String,
  val title: String?,
  val author: String,
  val device: DeviceMatch?,
)

/**
 * The designs among [designs] that can draw [key] for a card on [device]: distinct ids among the
 * live entries for the key (any variant or author) that are generic or made for a device that
 * matches. Manifest order, so the library's design comes first; a design listed by both a generic
 * and a device entry shows the most specific matching device.
 */
fun designOptions(
  designs: List<DesignEntry>,
  key: DesignKey,
  device: DeviceInfo?,
): List<DesignOption> {
  val fits = designs.filter { entry ->
    !entry.revoked &&
      entry.stableDesignId != null &&
      entry.key.sameExceptVariant(key) &&
      (entry.device == null || (device != null && entry.device?.matches(device) == true))
  }
  return fits
    .groupBy { it.stableDesignId!! }
    .map { (id, entries) ->
      val best = entries.maxBy { it.device?.specificity ?: 0 }
      DesignOption(
        designId = id,
        title = entries.firstNotNullOfOrNull { it.title },
        author = best.author,
        device = best.device,
      )
    }
}

private fun DesignKey.sameExceptVariant(other: DesignKey): Boolean =
  copy(variant = DesignKey.DEFAULT_VARIANT) == other.copy(variant = DesignKey.DEFAULT_VARIANT)

fun cachedDesignRepository(
  store: DesignStore,
  inbuilt: InbuiltDesigns = InbuiltDesigns.None,
  clock: () -> Long,
): DesignRepository = DesignRepository(DesignServerConfig.Off, store, null, inbuilt, clock)
