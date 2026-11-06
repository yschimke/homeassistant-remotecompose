package ee.schimke.ha.designserver

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import ee.schimke.composeai.rcplayer.compose.RcComposeTextRasterizer
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.client.design.CachedFontFace
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.CardFontManifest
import ee.schimke.ha.rc.HaFontLibrary
import ee.schimke.ha.rc.WidgetBitmapFonts
import ee.schimke.ha.rc.WidgetHost
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray

/**
 * The widget filters a client asks for a document it hands a widget host: today the bitmap-font
 * filter ([WidgetBitmapFonts]), which draws the card fonts this server serves ([CardFonts]) from
 * glyphs embedded in the document, for a host that can't load fonts. The glyphs are drawn here, on
 * the JVM, with the player's own text stack: neither a phone nor a watch can draw them yet.
 */
internal class WidgetFilters(fonts: CardFonts) {
  private val rasterizer = run {
    // Compose's desktop text registers itself when a scene starts, and this server starts none.
    // Internal API, but the jars it binds are pinned to the one Compose version this build uses.
    @OptIn(InternalComposeUiApi::class) registerSkikoComposeImplementation()
    val manifest = CardFontManifest.decode(fonts.manifest.decodeToString())
    val faces =
      manifest.faces.mapNotNull { (family, face) ->
        val bytes = fonts.file(face.file) ?: return@mapNotNull null
        CachedFontFace(family, face.weight, face.italic, Sha256.hex(bytes), bytes)
      }
    RcComposeTextRasterizer(HaFontLibrary.loader(faces.groupBy { it.family.lowercase() }))
  }

  /** One filter at a time: the text stack draws on one thread. */
  private val lock = Mutex()

  /** Filtered documents by their source's hash, host and density: designs repeat across clients. */
  private val cache =
    object : LinkedHashMap<String, Filtered>(CACHE_SIZE, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Filtered>?) =
        size > CACHE_SIZE
    }

  /** [bytes] filtered for [host] at [density], or null when [bytes] isn't a document. */
  suspend fun bitmapFonts(bytes: ByteArray, host: WidgetHost, density: Float): Filtered? =
    lock.withLock {
      val key = "${Sha256.hex(bytes)}:${host.wire}:$density"
      cache[key]?.let {
        return@withLock it
      }
      val document = runCatching { RcDocumentCodec.decode(bytes, RcWireLimits()) }.getOrNull()
      if (document == null) return@withLock null
      val result = WidgetBitmapFonts.apply(document, host, rasterizer, RcDisplay(density))
      Filtered(
          RcDocumentCodec.encode(result.document),
          converted = result.texts.size,
          skipped = result.skipped.size + result.skippedOnPath.size,
        )
        .also { cache[key] = it }
    }

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
 * for (1–4, default 2); a Wear widget scales them with the watch's. `X-Bitmap-Fonts` says how many
 * texts were converted and how many left as they were.
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
      filters.bitmapFonts(body, host, density)
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
