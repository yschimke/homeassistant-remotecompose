package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.rc.LiveText
import ee.schimke.ha.rc.WidgetBitmapFonts
import ee.schimke.ha.rc.WidgetHost
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The widget filters a client asks for a document it hands a widget host: today the bitmap-font
 * filter ([WidgetBitmapFonts]), which draws the card fonts this server serves ([CardFonts]) from
 * glyphs embedded in the document, for a host that can't load fonts. The glyphs are drawn here, on
 * the JVM, with the player's own text stack: neither a phone nor a watch can draw them yet.
 */
internal class WidgetFilters(private val text: ServerText) {
  constructor(fonts: CardFonts) : this(ServerText(fonts))

  private val rasterizer
    get() = text.rasterizer

  private val lock
    get() = text.lock

  /** Filtered documents by their whole input: designs repeat across clients. */
  private val cache =
    object : LinkedHashMap<Key, Filtered>(CACHE_SIZE, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Filtered>?) =
        size > CACHE_SIZE
    }

  /** [bytes] filtered for [host] at [density], or null when [bytes] isn't a document. */
  suspend fun bitmapFonts(
    bytes: ByteArray,
    host: WidgetHost,
    density: Float,
    live: LiveText = LiveText(),
  ): Filtered? = lock.withLock {
    val key = Key(Sha256.hex(bytes), host, density, live)
    cache[key]?.let {
      return@withLock it
    }
    val document = runCatching { RcDocumentCodec.decode(bytes, RcWireLimits()) }.getOrNull()
    if (document == null) return@withLock null
    val result = WidgetBitmapFonts.apply(document, host, rasterizer, RcDisplay(density), live)
    Filtered(
        RcDocumentCodec.encode(result.document),
        converted = result.texts.size,
        skipped = result.skipped.size + result.skippedOnPath.size,
      )
      .also { cache[key] = it }
  }

  /** A filter's whole input, compared by value: two inputs never share an entry. */
  private data class Key(
    val sha256: String,
    val host: WidgetHost,
    val density: Float,
    val live: LiveText,
  )

  class Filtered(val bytes: ByteArray, val converted: Int, val skipped: Int)

  companion object {
    private const val CACHE_SIZE = 256

    /** The largest document a client may send: well past a widget's budget. */
    const val MAX_BYTES: Int = 512 * 1024
  }
}

/**
 * `POST <base>/filters/bitmap-fonts?host=wear-widgets|launcher-widgets&density=<dp scale>`: the
 * request body, a Remote Compose document, back with its card fonts drawn from embedded bitmap
 * fonts for that widget host ([WidgetBitmapFonts]). `density` is the display the glyphs are drawn
 * for (1–4, default 2); a Wear widget scales them with the watch's. `locale`, `chars`, `values` and
 * `maxLength` say what text the card fills in later may show ([LiveText]). `X-Bitmap-Fonts` says
 * how many texts were converted and how many left as they were.
 */
internal fun Route.widgetFilterRoutes(base: String, filters: WidgetFilters) {
  post("$base/filters/bitmap-fonts") {
    call.allowAnyOrigin()
    val host =
      WidgetHost.of(call.request.queryParameters["host"])
        ?: return@post call.respondText(
          "host must be one of ${WidgetHost.entries.joinToString { it.wire }}",
          status = HttpStatusCode.BadRequest,
        )
    val density = call.request.queryParameters["density"]?.toFloatOrNull() ?: DEFAULT_DENSITY
    val live = runCatching {
      liveText(call.request.queryParameters)
    }
      .getOrElse {
        return@post call.respondText(
          it.message ?: "bad live text",
          status = HttpStatusCode.BadRequest,
        )
      }
    if (density !in 1f..4f) {
      return@post call.respondText("density must be 1 to 4", status = HttpStatusCode.BadRequest)
    }
    val body = call.receiveChannel().readRemaining(WidgetFilters.MAX_BYTES + 1L).readByteArray()
    if (body.size > WidgetFilters.MAX_BYTES) {
      return@post call.respondText(
        "a document over ${WidgetFilters.MAX_BYTES} bytes",
        status = HttpStatusCode.PayloadTooLarge,
      )
    }
    val filtered =
      filters.bitmapFonts(body, host, density, live)
        ?: return@post call.respondText(
          "not a Remote Compose document",
          status = HttpStatusCode.UnprocessableEntity,
        )
    call.response.header(
      "X-Bitmap-Fonts",
      "converted=${filtered.converted}; skipped=${filtered.skipped}",
    )
    call.respondBytes(filtered.bytes, ContentType.Application.OctetStream)
  }
}

private const val DEFAULT_DENSITY = 2f

/**
 * The [LiveText] a request asks for: `locale` (a BCP 47 tag), `chars`, `values` (a JSON object of
 * each binding's possible values: `{"sensor.s0.state":["on","off"]}`) and `maxLength`.
 */
private fun liveText(parameters: Parameters): LiveText {
  val values =
    parameters["values"]?.let { text ->
      Json.parseToJsonElement(text).jsonObject.mapValues { (_, list) ->
        list.jsonArray.map { it.jsonPrimitive.content }
      }
    }
  val maxLength =
    parameters["maxLength"]?.let { it.toIntOrNull() ?: error("maxLength must be a number") }
  require(maxLength == null || maxLength in 1..MAX_LIVE_LENGTH) {
    "maxLength must be 1 to $MAX_LIVE_LENGTH"
  }
  require((parameters["chars"]?.length ?: 0) <= MAX_LIVE_CHARS) {
    "chars must be at most $MAX_LIVE_CHARS"
  }
  return LiveText(
    locale = parameters["locale"]?.takeIf { it.isNotBlank() },
    chars = parameters["chars"].orEmpty(),
    values = values.orEmpty(),
    maxLength = maxLength ?: WidgetBitmapFonts.DEFAULT_LIVE_LENGTH,
  )
}

/** The longest live text a request may ask a slot for. */
private const val MAX_LIVE_LENGTH = 64

/** The most extra characters a request may ask for: a glyph each. */
private const val MAX_LIVE_CHARS = 256
