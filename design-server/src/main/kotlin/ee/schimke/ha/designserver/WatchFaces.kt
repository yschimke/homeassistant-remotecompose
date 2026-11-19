package ee.schimke.ha.designserver

import ee.schimke.composeai.dwf.WffCompileOptions
import ee.schimke.composeai.dwf.WffRemoteCompose
import ee.schimke.composeai.dwf.WffResourceSource
import ee.schimke.composeai.dwf.WffSeverity
import ee.schimke.composeai.dwf.WffValue
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.designs.JvmComposeGraphics
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Published Wear OS watch faces as cards: a `watchface` card plays a face someone published on a
 * Watch Face Push site (ui.coo.ee, preview.coo.ee; watchface-tools' `docs/PUSH_API.md`).
 *
 * ```yaml
 * type: custom:remote-compose-card
 * card:
 *   type: watchface
 *   face: aurora              # a design id from the site's GET /designs
 *   server: https://ui.coo.ee # optional; one of [servers]
 *   size: 450                 # optional; the document's side in px (64..1024)
 * ```
 *
 * [resolve] lists the site's public faces, downloads the face's APK (checked against the listing's
 * `sha256`), reads its Watch Face Format XML and `res/` tree, and compiles them to a `.rc` with
 * compose-ai-tools' translator ([WffRemoteCompose]). The face reads the player's own clock (its
 * system time variables), so it ticks with no values pushed; its other named values (`dwf.*`,
 * `metrics.*`, complications) are answered with the defaults the face was compiled with.
 *
 * Only the [servers] listed are ever fetched from, and an APK only from one of their hosts, so a
 * card's config can't make this server fetch an arbitrary URL.
 */
class WatchFaces(
  private val fetcher: Fetcher,
  val servers: List<String> = DEFAULT_SERVERS,
  private val now: () -> Long = System::currentTimeMillis,
) {
  /** An HTTP GET with a size cap: the body, or null for a non-2xx answer. */
  fun interface Fetcher {
    suspend fun get(url: String, maxBytes: Long): ByteArray?
  }

  /** Why a watch face card can't be drawn, with the HTTP status the resolve route answers. */
  class Problem(val status: HttpStatusCode, message: String) : Exception(message)

  /** One entry of a site's `GET /api/watchface-push/v1/designs`; the fields this server reads. */
  @Serializable
  data class PublishedFace(
    val id: String,
    val title: String = "",
    val version: Long = 0,
    val wffVersion: Int = WffCompileOptions.DEFAULT_WFF_VERSION,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0,
    val previewUrl: String? = null,
  )

  @Serializable private data class DesignList(val designs: List<PublishedFace> = emptyList())

  /** A compiled face: its `.rc`, under its sha, and the values it was compiled with. */
  data class Compiled(
    val face: PublishedFace,
    val sha256: String,
    val bytes: ByteArray,
    val bindings: Map<String, JsonPrimitive>,
    val notes: List<String>,
  )

  private data class Listing(val faces: List<PublishedFace>, val fetchedAt: Long)

  private data class CompileKey(
    val server: String,
    val id: String,
    val version: Long,
    val px: Int,
    val sweep: Boolean,
    val seconds: Boolean,
  )

  private val listings = mutableMapOf<String, Listing>()
  private val compiled =
    object : LinkedHashMap<CompileKey, Compiled>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CompileKey, Compiled>) =
        size > MAX_FACES
    }
  private val lock = Mutex()

  /** The faces [server] lists publicly, newest first, kept for [LISTING_TTL_MS]. */
  suspend fun faces(server: String): List<PublishedFace> {
    val base = allowedServer(server)
    lock.withLock {
      listings[base]
        ?.takeIf { now() - it.fetchedAt < LISTING_TTL_MS }
        ?.let {
          return it.faces
        }
    }
    val body =
      fetcher.get("$base$DESIGNS_PATH", MAX_LISTING_BYTES)
        ?: throw Problem(HttpStatusCode.BadGateway, "$base didn't list its watch faces")
    val faces = runCatching {
      json.decodeFromString(DesignList.serializer(), body.decodeToString()).designs
    }
      .getOrElse { throw Problem(HttpStatusCode.BadGateway, "$base's face list is unreadable") }
    lock.withLock { listings[base] = Listing(faces, now()) }
    return faces
  }

  /**
   * The `.rc` for [faceId] on [server] at [px] by [px], compiled once per published version.
   * Without [sweep], second hands tick once a second instead of sweeping ([withoutSweep]): a player
   * then draws the face once a second, not every frame.
   */
  suspend fun compile(
    server: String,
    faceId: String,
    px: Int = DEFAULT_PX,
    sweep: Boolean = true,
    seconds: Boolean = true,
  ): Compiled {
    val base = allowedServer(server)
    require(px in MIN_PX..MAX_PX) { "size must be $MIN_PX..$MAX_PX" }
    val face =
      faces(base).firstOrNull { it.id == faceId }
        ?: throw Problem(HttpStatusCode.NotFound, "$base publishes no watch face \"$faceId\"")
    val key = CompileKey(base, face.id, face.version, px, sweep, seconds)
    lock.withLock {
      compiled[key]?.let {
        return it.also { WatchFaceBlobs.put(it) }
      }
    }

    if (!sameSite(base, face.apkUrl)) {
      throw Problem(HttpStatusCode.BadGateway, "\"$faceId\"'s APK isn't served by $base")
    }
    val apk =
      fetcher.get(face.apkUrl, MAX_APK_BYTES)
        ?: throw Problem(HttpStatusCode.BadGateway, "couldn't download \"$faceId\"")
    if (!sha256(apk).equals(face.sha256, ignoreCase = true)) {
      throw Problem(HttpStatusCode.BadGateway, "\"$faceId\"'s APK doesn't match its published hash")
    }
    val bundle =
      WatchFaceApk.read(apk)
        ?: throw Problem(HttpStatusCode.UnprocessableEntity, "\"$faceId\" has no watch face XML")
    // The translator decodes and draws the face's images through Compose's graphics, which a
    // server that has captured nothing yet hasn't registered (#1464).
    JvmComposeGraphics.ensureRegistered()
    val result =
      withContext(Dispatchers.Default) {
        WffRemoteCompose.compile(
          bundle.xml
            .let { if (sweep) it else withoutSweep(it) }
            .let { if (seconds) it else withoutSecondHands(it) },
          WffResourceSource.of(bundle.resources),
          WffCompileOptions(
            widthPx = px,
            heightPx = px,
            wffVersion =
              face.wffVersion.coerceIn(
                WffCompileOptions.MIN_WFF_VERSION,
                WffCompileOptions.MAX_WFF_VERSION,
              ),
          ),
        )
      }
    val bytes =
      result.rcBytes?.takeIf { result.isSuccess }
        ?: throw Problem(
          HttpStatusCode.UnprocessableEntity,
          "\"$faceId\" didn't compile: " +
            result.diagnostics
              .filter { it.severity == WffSeverity.ERROR }
              .joinToString("; ") { it.message },
        )
    val notes = buildList {
      result.diagnostics
        .filter { it.severity == WffSeverity.WARNING }
        .take(MAX_NOTES)
        .forEach { add(it.message) }
      if (bundle.skipped > 0) add("${bundle.skipped} compiled resources weren't readable.")
    }
    val out =
      Compiled(
        face = face,
        sha256 = sha256(bytes),
        bytes = bytes,
        bindings = result.namedValues.associate { it.name to it.default.toJson() },
        notes = notes,
      )
    lock.withLock { compiled[key] = out }
    WatchFaceBlobs.put(out)
    return out
  }

  /**
   * A `watchface` card's design, or null for any other card. Its second hand sweeps as its author
   * made it unless the card says `sweep: false`; for a [smallPlayer] (a wall panel, which draws
   * only what changed and pays ~10 ms a frame for it) it ticks unless the card says `sweep: true`.
   */
  suspend fun resolve(card: JsonObject, smallPlayer: Boolean = false): CardMatch? {
    if (text(card, "type") != CARD_TYPE) return null
    val faceId =
      text(card, "face")?.takeIf { it.isNotBlank() }
        ?: throw Problem(HttpStatusCode.BadRequest, "a watchface card needs `face`")
    val server = text(card, "server") ?: servers.first()
    val px = (card["size"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_PX
    if (px !in MIN_PX..MAX_PX) {
      throw Problem(HttpStatusCode.BadRequest, "`size` must be $MIN_PX..$MAX_PX")
    }
    val sweep = (card["sweep"] as? JsonPrimitive)?.booleanOrNull ?: !smallPlayer
    // A host that redraws only now and then (a launcher widget, pushed once a minute) has no use
    // for a second hand that would stand still between pushes.
    val seconds = (card["seconds"] as? JsonPrimitive)?.booleanOrNull ?: true
    val face = compile(server, faceId, px, sweep, seconds)
    return CardMatch(
      design = "$CARD_TYPE:${face.face.id}",
      sha256 = face.sha256,
      blob = "$API_BASE/blobs/${face.sha256}.rc",
      key =
        DesignKey(
          cardType = CARD_TYPE,
          surface = DesignSurface.MobileApp,
          rcApiLevel = 0,
          shape =
            "face=${face.face.id};version=${face.face.version};px=$px" +
              (if (sweep) "" else ";tick") +
              if (seconds) "" else ";no-seconds",
          theme = "style:$CARD_TYPE:dark",
        ),
      exact = true,
      bindings = face.bindings,
      notes = face.notes,
    )
  }

  private fun allowedServer(server: String): String {
    val base = normalize(server)
    if (base == null || base !in servers) {
      throw Problem(
        HttpStatusCode.BadRequest,
        "watch faces come from ${servers.joinToString(" or ")}, not $server",
      )
    }
    return base
  }

  companion object {
    const val CARD_TYPE = "watchface"
    const val DESIGNS_PATH = "/api/watchface-push/v1/designs"
    val DEFAULT_SERVERS = listOf("https://ui.coo.ee", "https://preview.coo.ee")
    const val DEFAULT_PX = 450
    const val MIN_PX = 64
    const val MAX_PX = 1024
    const val LISTING_TTL_MS = 10 * 60 * 1000L
    const val MAX_LISTING_BYTES = 2L * 1024 * 1024
    const val MAX_APK_BYTES = 16L * 1024 * 1024
    private const val MAX_FACES = 64
    private const val MAX_NOTES = 5

    private val json = Json { ignoreUnknownKeys = true }

    /** `https://host[:port]` with no path, query or user info; null if it isn't one. */
    fun normalize(server: String): String? {
      val uri =
        runCatching { URI(server.trim().let { if ("://" in it) it else "https://$it" }) }
          .getOrNull() ?: return null
      if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty()) return null
      if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
      if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
      val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
      return "https://${uri.host.lowercase()}$port"
    }

    /** Whether [url] is https on [base]'s host: the APK is fetched from nowhere else. */
    internal fun sameSite(base: String, url: String): Boolean {
      val uri = runCatching { URI(url) }.getOrNull() ?: return false
      return uri.scheme?.lowercase() == "https" &&
        uri.rawUserInfo == null &&
        normalize("https://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}") == base
    }

    /** The sites [Main] reads from `WATCHFACE_SERVERS` (comma-separated), else the defaults. */
    fun serversFrom(env: String?): List<String> =
      env
        ?.split(',')
        ?.mapNotNull { it.takeIf(String::isNotBlank)?.let(::normalize) }
        ?.takeIf { it.isNotEmpty() } ?: DEFAULT_SERVERS

    /**
     * A [Fetcher] over Ktor that streams the body and stops reading at the cap, so an oversized
     * answer is never held whole. [http] must not follow redirects ([httpClient]): only the URL
     * [WatchFaces] checked is fetched, never where an answer points.
     */
    fun httpFetcher(http: HttpClient) = Fetcher { url, maxBytes ->
      http.prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) return@execute null
        response.contentLength()?.let {
          if (it > maxBytes) throw Problem(HttpStatusCode.BadGateway, "$url is too large")
        }
        val channel = response.bodyAsChannel()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
          val n = channel.readAvailable(buffer)
          if (n < 0) break
          if (out.size() + n > maxBytes) {
            throw Problem(HttpStatusCode.BadGateway, "$url is too large")
          }
          out.write(buffer, 0, n)
        }
        out.toByteArray()
      }
    }

    /** The client [httpFetcher] needs: redirects are answers, never followed. */
    fun httpClient(): HttpClient =
      HttpClient(io.ktor.client.engine.cio.CIO) { followRedirects = false }

    private val SWEEP = Regex("""<Sweep\b[^>]*?(/>|>.*?</Sweep\s*>)""", RegexOption.DOT_MATCHES_ALL)

    /**
     * [xml] with its `<Sweep>` elements removed: a hand with one follows the continuous clock
     * (`[SECOND_MILLISECOND]`), so the face needs a frame every vsync; without, it reads whole
     * seconds and needs one a second, the way a watch's own ambient-off tick hand does.
     */
    internal fun withoutSweep(xml: String): String = SWEEP.replace(xml, "")

    private val SECOND_HAND =
      Regex("""<SecondHand\b[^>]*?(/>|>.*?</SecondHand\s*>)""", RegexOption.DOT_MATCHES_ALL)

    /**
     * [xml] without its `<SecondHand>` elements, for `seconds: false`. A face whose hour and minute
     * hands still read `[SECOND]` keeps moving them smoothly; only the hand is gone.
     */
    internal fun withoutSecondHands(xml: String): String = SECOND_HAND.replace(xml, "")

    private fun text(card: JsonObject, name: String) =
      (card[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun WffValue.toJson(): JsonPrimitive =
      when (this) {
        is WffValue.Text -> JsonPrimitive(value)
        is WffValue.Float -> JsonPrimitive(value)
        is WffValue.Int -> JsonPrimitive(value)
        is WffValue.Bool -> JsonPrimitive(if (value) 1 else 0)
        is WffValue.Color -> JsonPrimitive(argb)
      }
  }
}

/** Compiled faces' `.rc` by sha, for the blob route: bounded, like [PlayerFolding]'s. */
object WatchFaceBlobs {
  private const val MAX_BLOBS = 64
  private val blobs =
    object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) =
        size > MAX_BLOBS
    }

  fun put(face: WatchFaces.Compiled) = synchronized(this) { blobs[face.sha256] = face.bytes }

  fun blob(sha256: String): ByteArray? = synchronized(this) { blobs[sha256] }
}

/**
 * A Watch Face Push APK's watch face: `res/raw/watchface.xml` (raw resources are stored as written,
 * never compiled to binary XML) and the rest of `res/` keyed res-relative, as the translator reads
 * it. Compiled XML (a vector drawable in binary XML) is skipped and counted: the translator draws a
 * placeholder for a resource it can't read.
 */
internal object WatchFaceApk {
  data class Bundle(val xml: String, val resources: Map<String, ByteArray>, val skipped: Int)

  private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
  private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

  fun read(apk: ByteArray): Bundle? {
    val resources = mutableMapOf<String, ByteArray>()
    var skipped = 0
    var total = 0L
    var xml: String? = null
    ZipInputStream(ByteArrayInputStream(apk)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val name = entry.name
        // Every entry is inflated, kept or not (the next getNextEntry drains it), so every entry
        // is read under the caps: an entry outside res/ is no way around them.
        val bytes = readCapped(zip) ?: return null
        total += bytes.size
        if (total > MAX_TOTAL_BYTES) return null
        if (entry.isDirectory || !name.startsWith("res/") || ".." in name) continue
        val path = name.removePrefix("res/")
        if (path.endsWith(".xml") && isBinaryXml(bytes)) {
          skipped++
          continue
        }
        if (path.startsWith("raw/") && path.endsWith(".xml")) {
          val text = bytes.decodeToString()
          if (xml == null && "<WatchFace" in text) xml = text
          if (path == "raw/watchface.xml" && "<WatchFace" in text) xml = text
          continue
        }
        resources[path] = bytes
      }
    }
    return xml?.let { Bundle(it, resources, skipped) }
  }

  /** Android's binary XML starts with a `RES_XML_TYPE` chunk header: `03 00 08 00`. */
  private fun isBinaryXml(bytes: ByteArray) =
    bytes.size >= 4 && bytes[0] == 3.toByte() && bytes[1] == 0.toByte() && bytes[2] == 8.toByte()

  private fun readCapped(zip: ZipInputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
      val n = zip.read(buffer)
      if (n < 0) break
      if (out.size() + n > MAX_ENTRY_BYTES) return null
      out.write(buffer, 0, n)
    }
    return out.toByteArray()
  }
}

private fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
