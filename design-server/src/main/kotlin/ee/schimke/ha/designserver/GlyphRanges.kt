package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.runtime.RcStyledText
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.glyphs.GlyphPack
import ee.schimke.ha.client.design.glyphs.GlyphPackCodec
import ee.schimke.ha.client.design.glyphs.GlyphRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.withLock

/**
 * A face's glyphs a character range at a time ([GlyphPack],
 * `docs/architecture/glyph-range-fonts.md`): for a player that keeps a document's text as text and
 * draws a face it can't load from glyphs it fetches and caches, rather than from glyphs embedded in
 * every document ([WidgetFilters]). Drawn as `coreTextAsBitmapFonts` draws a bitmap font's: white
 * masks, advances measured at eight times the size, every glyph on the face's lowest baseline.
 */
internal class GlyphRanges(
  private val text: ServerText,
  /** Where a `google:` family no card font names comes from; null serves only the server's own. */
  private val google: GoogleFonts? = null,
) {
  /** Subset fonts by their whole request, and one subsetting at a time. */
  private val fonts =
    object : LinkedHashMap<GlyphQuery, Rendered>(CACHE_SIZE, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphQuery, Rendered>?) =
        size > CACHE_SIZE
    }
  private val subsets = kotlinx.coroutines.sync.Mutex()

  /** Packs by their whole request: a face at a size is asked for by every device that shows it. */
  private val cache =
    object : LinkedHashMap<GlyphQuery, Rendered>(CACHE_SIZE, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphQuery, Rendered>?) =
        size > CACHE_SIZE
    }

  /** A reply: a pack, or (format=font) a subset font, its ETag and how many glyphs it holds. */
  class Rendered(
    val bytes: ByteArray,
    val etag: String,
    val glyphs: Int,
    val contentType: String = GlyphPack.CONTENT_TYPE,
  )

  /**
   * [query]'s pack, or null when the server has no such family: its own faces first
   * (`google:roboto` is the wall's Roboto), then, for a `google:` family, Google Fonts' face.
   */
  suspend fun glyphs(query: GlyphQuery): Rendered? {
    // `emoji`: the colour emoji face, whatever a client calls it by.
    val source = if (query.family.equals(EMOJI, ignoreCase = true)) EMOJI_FACE else query.family
    val local = text.facesOf(source)
    // Fetched before the text stack is taken: a download shouldn't hold up every other request.
    val googleFace =
      if (local.isNotEmpty() || !source.startsWith(GOOGLE_PREFIX, ignoreCase = true)) null
      else google?.face(source.drop(GOOGLE_PREFIX.length).trim(), query.weight, query.italic)
    if (local.isEmpty() && googleFace == null) return null
    val family = local.firstOrNull()?.family ?: googleFace!!.family
    val shas = local.map { it.sha256 } + listOfNotNull(googleFace?.sha256)
    if (query.format == GlyphQuery.FORMAT_FONT) {
      // The face nearest the weight and slant asked for, as a font matcher would pick it.
      val bytes =
        googleFace?.bytes
          ?: local
            .minWith(
              compareBy(
                { it.italic != query.italic },
                { kotlin.math.abs(it.weight - query.weight) },
              ),
            )
            .bytes
      return subsets.withLock {
        fonts[query]?.let {
          return@withLock it
        }
        val subset = FontSubsets.subset(bytes, query.chars) ?: return@withLock null
        val etag =
          "\"" +
            Sha256.hex("font ${subset.sha} $query ${shas.joinToString(",")}".encodeToByteArray()) +
            "\""
        Rendered(subset.bytes, etag, subset.glyphs, FONT_TYPE).also { fonts[query] = it }
      }
    }
    return text.lock.withLock {
      cache[query]?.let {
        return@withLock it
      }
      val pack = render(query, family, googleFace?.bytes)
      val etag =
        "\"" + Sha256.hex("$RENDERING $query ${shas.joinToString(",")}".encodeToByteArray()) + "\""
      Rendered(GlyphPackCodec.encode(pack, query.bits), etag, pack.glyphs.size).also {
        cache[query] = it
      }
    }
  }

  private fun styled(
    query: GlyphQuery,
    family: String,
    fontData: ByteArray?,
    text: String,
    scale: Float = 1f,
  ) =
    RcStyledText(
      text = text,
      density = query.density,
      // The rasterizer takes device pixels: density only says how many a dp is.
      fontSize = query.size * query.density * scale,
      color = WHITE,
      fontWeight = query.weight,
      italic = query.italic,
      fontFamily = family,
      fontData = fontData,
      maxLines = 1,
    )

  private fun advance(
    query: GlyphQuery,
    family: String,
    fontData: ByteArray?,
    text: String,
  ): Float? =
    this.text.rasterizer
      .rasterize(styled(query, family, fontData, text, MEASURE_SCALE))
      ?.width
      ?.let { it / MEASURE_SCALE }

  /** The box of [Drawn.coverage]'s covered pixels, left, top, right, bottom, or null if none. */
  private fun Drawn.ink(): List<Int>? {
    var x0 = width
    var y0 = height
    var x1 = -1
    var y1 = -1
    for (y in 0 until height) for (x in 0 until width) {
      if (coverage[y * width + x].toInt() != 0) {
        x0 = minOf(x0, x)
        x1 = maxOf(x1, x)
        y0 = minOf(y0, y)
        y1 = maxOf(y1, y)
      }
    }
    return if (x1 < 0) null else listOf(x0, y0, x1, y1)
  }

  private class Drawn(
    val chars: String,
    val width: Int,
    val height: Int,
    val baseline: Float,
    val advance: Float,
    val coverage: ByteArray,
    /** The raster's premultiplied ARGB, for a colour pack. */
    val argb: IntArray?,
  )

  private fun render(query: GlyphQuery, family: String, fontData: ByteArray?): GlyphPack {
    val drawn =
      query.chars.sorted().mapNotNull { chars ->
        val raster =
          text.rasterizer.rasterize(styled(query, family, fontData, chars))
            ?: return@mapNotNull null
        if (raster.width !in 1..Short.MAX_VALUE || raster.height !in 1..Short.MAX_VALUE) {
          return@mapNotNull null
        }
        val image = ImageIO.read(raster.png.inputStream()) ?: return@mapNotNull null
        val coverage =
          ByteArray(image.width * image.height) { i ->
            (image.getRGB(i % image.width, i / image.width) ushr 24).toByte()
          }
        // A colour pack keeps the pixels (an emoji's), premultiplied as a player blends them.
        val argb =
          if (query.bits != GlyphPackCodec.COLOR_BITS) null
          else
            IntArray(image.width * image.height) { i ->
              val p = image.getRGB(i % image.width, i / image.width)
              val a = p ushr 24
              fun ch(shift: Int) = (((p ushr shift) and 0xff) * a + 127) / 255
              (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        val advance = advance(query, family, fontData, chars) ?: return@mapNotNull null
        Drawn(chars, image.width, image.height, raster.baseline, advance, coverage, argb)
      }
    // Every glyph on the lowest baseline: a margin above each sets its own baseline there.
    val baseline = drawn.map { it.baseline }.filterNot { it.isNaN() }.maxOrNull()
    fun above(glyph: Drawn) = baseline?.let { (it - glyph.baseline).roundToInt() } ?: 0
    val lineHeight = drawn.maxOfOrNull { above(it) + it.height } ?: 0
    val glyphs = drawn.map { glyph ->
      // The image is the advance rounded up to a pixel; the advance itself to a pixel, so a line
      // keeps its length rather than gaining up to a pixel a glyph.
      val advance = glyph.advance.roundToInt().coerceAtMost(glyph.width)
      // Kept to its ink: a raster is its line's whole cell, half of it clear. The margins place
      // what's left where the cell had it; a glyph with no ink (a space) is its advance alone.
      val ink = glyph.ink()
      if (ink == null) {
        GlyphPack.Glyph(glyph.chars, advance, 0, 0, lineHeight, 0, 0, ByteArray(0))
      } else {
        val (x0, y0, x1, y1) = ink
        val width = x1 - x0 + 1
        val height = y1 - y0 + 1
        val top = above(glyph) + y0
        GlyphPack.Glyph(
          chars = glyph.chars,
          marginLeft = x0,
          marginTop = top,
          marginRight = advance - x0 - width,
          marginBottom = lineHeight - top - height,
          width = width,
          height = height,
          coverage =
            ByteArray(width * height) { i ->
              glyph.coverage[(y0 + i / width) * glyph.width + x0 + i % width]
            },
          argb =
            glyph.argb?.let { px ->
              IntArray(width * height) { i -> px[(y0 + i / width) * glyph.width + x0 + i % width] }
            },
        )
      }
    }
    val kerning =
      if (!query.kerning || drawn.size > MAX_KERNED) emptyMap()
      else {
        val exact = drawn.associate { it.chars to it.advance }
        buildMap {
          for (a in drawn) for (b in drawn) {
            val pair = advance(query, family, fontData, a.chars + b.chars) ?: continue
            val kern = (pair - exact.getValue(a.chars) - exact.getValue(b.chars)).roundToInt()
            if (kern != 0) put(a.chars + b.chars, kern)
          }
        }
      }
    return GlyphPack(
      family = query.family,
      size = query.size,
      density = query.density,
      weight = query.weight,
      italic = query.italic,
      lineHeight = lineHeight,
      baseline = baseline?.roundToInt() ?: 0,
      glyphs = glyphs,
      kerning = kerning,
    )
  }

  companion object {
    private const val CACHE_SIZE = 256

    private const val GOOGLE_PREFIX = "google:"

    /** The family a client asks for colour emoji by, and the face that draws them. */
    const val EMOJI = "emoji"
    private const val EMOJI_FACE = "google:Noto Color Emoji"

    const val FONT_TYPE = "font/ttf"

    /**
     * How glyphs are drawn, in every ETag: raise it when a change draws the same request
     * differently, so a cached pack doesn't revalidate as unchanged.
     */
    private const val RENDERING = 4

    /** How much larger a glyph or pair is laid out to measure its advance, as the filter does. */
    private const val MEASURE_SCALE = 8f

    /** The most characters a request's pairs are kerned for: every pair is measured. */
    const val MAX_KERNED = 32

    /** The most characters a request may ask for. */
    const val MAX_CHARS = 512

    private const val WHITE = 0xffffffff.toInt()
  }
}

/**
 * What a client asks [GlyphRanges] for: a face at a size, its characters, the depth they travel.
 */
internal data class GlyphQuery(
  val family: String,
  val size: Float,
  val density: Float,
  val weight: Int,
  val italic: Boolean,
  val chars: Set<String>,
  val bits: Int,
  val kerning: Boolean,
  /** [FORMAT_RASTER], a glyph pack, or [FORMAT_FONT], the face subset to [chars] as TrueType. */
  val format: String = FORMAT_RASTER,
) {
  companion object {
    /** [parameters] as a query, or the reason they aren't one. */
    fun parse(parameters: Parameters): Result<GlyphQuery> = runCatching {
      val family = parameters["family"]?.takeIf { it.isNotBlank() } ?: error("family is required")
      val font = parameters["format"] == FORMAT_FONT
      // A font is outlines, any size: the size is a pack's.
      val size = parameters["size"]?.toFloatOrNull() ?: if (font) 14f else error("size is required")
      val density =
        parameters["density"]?.let { it.toFloatOrNull() ?: error("density must be a number") } ?: 1f
      require(density in 1f..4f) { "density must be 1 to 4" }
      require(font || size in 4f..256f) { "size must be 4 to 256" }
      val weight =
        parameters["weight"]?.let { it.toIntOrNull() ?: error("weight must be a number") } ?: 400
      require(weight in 1..1000) { "weight must be 1 to 1000" }
      val italic = parameters["italic"] == "1" || parameters["italic"] == "true"
      val bits = parameters["bits"]?.let { it.toIntOrNull() ?: error("bits must be a number") } ?: 8
      require(bits in GlyphPackCodec.BITS) { "bits must be one of ${GlyphPackCodec.BITS}" }
      val chars =
        (parameters["chars"]?.let(GlyphRequest::charsOf).orEmpty() +
            parameters["ranges"]?.let(::ranges).orEmpty())
          // A glyph is one code point (an emoji may be past the basic plane), drawn left to
          // right; joiners and variation selectors draw nothing on their own.
          .filter { c ->
            val cp = c.codePointAt(0)
            c.length == Character.charCount(cp) &&
              cp >= ' '.code &&
              cp != 0x200D &&
              cp !in 0xFE00..0xFE0F
          }
          .toSet()
      require(chars.isNotEmpty()) { "chars or ranges is required" }
      require(chars.size <= GlyphRanges.MAX_CHARS) { "at most ${GlyphRanges.MAX_CHARS} characters" }
      val format = parameters["format"] ?: FORMAT_RASTER
      require(format in setOf(FORMAT_RASTER, FORMAT_FONT)) { "format must be raster or font" }
      GlyphQuery(
          family,
          size,
          density,
          weight,
          italic,
          chars,
          // A font is outlines: the depth, the size and kerning are the client's to draw.
          if (format == FORMAT_FONT) 8 else bits,
          format != FORMAT_FONT && parameters["kerning"] == "1",
          format,
        )
        .let { if (format == FORMAT_FONT) it.copy(size = 0f, density = 1f) else it }
    }

    const val FORMAT_RASTER = "raster"
    const val FORMAT_FONT = "font"

    /** `0x20-0x7e,0xb0,65-70`: code points, inclusive. */
    private fun ranges(text: String): Set<String> = buildSet {
      for (part in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val bounds = part.split('-').map(::codePoint)
        require(bounds.size in 1..2) { "a range is from-to: $part" }
        val (from, to) = bounds.first() to bounds.last()
        require(from <= to && to - from < GlyphRanges.MAX_CHARS * 4) {
          "a range runs forwards: $part"
        }
        for (cp in from..to) if (cp <= 0xffff) add(cp.toChar().toString())
        require(size <= GlyphRanges.MAX_CHARS * 4) { "too many characters" }
      }
    }

    private fun codePoint(text: String): Int {
      val t = text.trim().lowercase()
      return (if (t.startsWith("0x")) t.drop(2).toIntOrNull(16) else t.toIntOrNull())?.takeIf {
        it in 0..0x10ffff
      } ?: error("not a code point: $text")
    }
  }
}

/**
 * `GET <base>/fonts/glyphs?family=&size=&density=&weight=&italic=&chars=|ranges=&bits=&kerning=`: a
 * face's glyphs as a [GlyphPack] (`application/vnd.ha.glyphs`). `X-Glyphs` says how many it holds;
 * a character the face can't draw is left out.
 */
internal fun Route.glyphRangeRoutes(base: String, glyphs: GlyphRanges) {
  get("$base/fonts/glyphs") {
    call.allowAnyOrigin()
    val query =
      GlyphQuery.parse(call.request.queryParameters).getOrElse {
        return@get call.respondText(it.message ?: "bad request", status = HttpStatusCode.BadRequest)
      }
    val rendered =
      glyphs.glyphs(query)
        ?: return@get call.respondText(
          "no family ${query.family}",
          status = HttpStatusCode.NotFound,
        )
    call.response.header(HttpHeaders.ETag, rendered.etag)
    call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
    call.response.header("X-Glyphs", rendered.glyphs.toString())
    if (call.request.header(HttpHeaders.IfNoneMatch)?.let { rendered.etag in it } == true) {
      call.respondBytes(ByteArray(0), status = HttpStatusCode.NotModified)
    } else {
      call.respondBytes(rendered.bytes, ContentType.parse(rendered.contentType))
    }
  }
}
