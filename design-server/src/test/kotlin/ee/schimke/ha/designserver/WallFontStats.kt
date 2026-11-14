package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBitmapFontData
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcDrawBitmapFontTextRun
import ee.schimke.composeai.rcplayer.protocol.RcDrawBitmapTextAnchored
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * A local measurement, not a check: `WALL_FILTER_DIR=<dir>` (as for [WidgetFiltersTest]'s filter
 * tool, after it ran) with `WALL_NAMED=<file>` (the named values, `USER:name=value` lines) prints,
 * for each `.wall.rc`, what its glyphs cost at each bit depth, how many fonts differ only by
 * colour, how many glyphs the texts on screen need, and the original document's text styles; and
 * writes `<name>.depths.png`, sample lines at 8, 4, 2 and 1 bits a pixel.
 */
class WallFontStats {
  private fun alpha(bitmap: RcBitmapData): Array<IntArray>? {
    val image = ImageIO.read(bitmap.data.inputStream()) ?: return null
    return Array(image.height) { y -> IntArray(image.width) { x -> image.getRGB(x, y) ushr 24 } }
  }

  /** [a] as a palette PNG of [bits] a pixel: white at 2^bits alpha levels. */
  private fun palettePng(a: Array<IntArray>, bits: Int): ByteArray {
    val levels = 1 shl bits
    val h = a.size
    val w = a.firstOrNull()?.size ?: 0
    if (w == 0 || h == 0) return ByteArray(0)
    val alphas = ByteArray(levels) { (it * 255 / (levels - 1)).toByte() }
    val white = ByteArray(levels) { 0xFF.toByte() }
    val model = IndexColorModel(bits, levels, white, white, white, alphas)
    val image =
      BufferedImage(
        w,
        h,
        if (bits == 8) BufferedImage.TYPE_BYTE_INDEXED else BufferedImage.TYPE_BYTE_BINARY,
        model,
      )
    val raster = image.raster
    for (y in 0 until h) for (x in 0 until w) raster.setSample(x, y, 0, quantize(a[y][x], levels))
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  private fun quantize(alpha: Int, levels: Int): Int = (alpha * (levels - 1) + 127) / 255

  @Test
  fun measureWallGlyphs() {
    val dir = System.getenv("WALL_FILTER_DIR")?.let { File(it) } ?: return
    val named =
      System.getenv("WALL_NAMED")
        ?.let { File(it).readLines() }
        .orEmpty()
        .mapNotNull { line ->
          line
            .indexOf('=')
            .takeIf { it > 0 }
            ?.let { line.substring(0, it) to line.substring(it + 1) }
        }
        .toMap()
    for (file in
      dir.listFiles().orEmpty().filter { it.name.endsWith(".wall.rc") }.sortedBy { it.name }) {
      val doc = RcDocumentCodec.decode(file.readBytes(), RcWireLimits())
      val original =
        File(dir, file.name.removeSuffix(".wall.rc") + ".rc")
          .takeIf { it.exists() }
          ?.let { RcDocumentCodec.decode(it.readBytes(), RcWireLimits()) }
      report(
        file.name,
        doc,
        original,
        named,
        File(dir, file.name.removeSuffix(".wall.rc") + ".depths.png"),
      )
    }
  }

  private fun report(
    name: String,
    doc: RcDocument,
    original: RcDocument?,
    named: Map<String, String>,
    out: File,
  ) {
    val bitmaps = doc.operations.filterIsInstance<RcBitmapData>().associateBy { it.imageId }
    val fonts = doc.operations.filterIsInstance<RcBitmapFontData>()
    val alphas = HashMap<Int, Array<IntArray>?>()
    fun alphaOf(id: Int) = alphas.getOrPut(id) { bitmaps[id]?.let(::alpha) }
    val glyphIds =
      fonts.flatMap { f -> f.glyphs.map { it.bitmapId } }.filter { it != -1 }.distinct()

    fun sizes(ids: Collection<Int>): String {
      var png8888 = 0L
      var raw8 = 0L
      val pal = LongArray(4)
      for (id in ids) {
        val b = bitmaps[id] ?: continue
        png8888 += b.data.size
        val a = alphaOf(id) ?: continue
        raw8 += a.size.toLong() * (a.firstOrNull()?.size ?: 0)
        listOf(8, 4, 2, 1).forEachIndexed { i, bits -> pal[i] += palettePng(a, bits).size }
      }
      return "%d glyphs: PNG8888 %.1f KB, RAW8 %.1f KB, palette PNG 8-bit %.1f / 4-bit %.1f / 2-bit %.1f / 1-bit %.1f KB"
        .format(
          ids.size,
          png8888 / 1024.0,
          raw8 / 1024.0,
          pal[0] / 1024.0,
          pal[1] / 1024.0,
          pal[2] / 1024.0,
          pal[3] / 1024.0,
        )
    }
    println("== $name: ${fonts.size} fonts, document ${RcDocumentCodec.encode(doc).size / 1024} KB")
    println("  all glyphs: ${sizes(glyphIds)}")

    // Fonts that differ only by colour: the same characters with the same coverage.
    val signature = fonts.associate { f ->
      f.fontId to
        f.glyphs
          .sortedBy { it.chars }
          .joinToString("|") { g ->
            g.chars +
              ":" +
              (alphaOf(g.bitmapId)
                ?.joinToString(",") { it.joinToString("") { v -> (v / 16).toString(16) } }
                ?.hashCode() ?: 0)
          }
    }
    val shapes = signature.values.toSet().size
    // Fonts by their glyph set's shape alone (white masks tinted at draw): a face at a size.
    val firstOfShape =
      signature.entries.groupBy({ it.value }, { it.key }).values.map { it.first() }.toSet()
    val maskIds =
      fonts
        .filter { it.fontId in firstOfShape }
        .flatMap { f -> f.glyphs.map { it.bitmapId } }
        .filter { it != -1 }
        .distinct()
    println("  as white masks, $shapes fonts not ${fonts.size}: ${sizes(maskIds)}")

    // The texts on screen now: which characters each font needs.
    val textOf =
      doc.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }.toMutableMap()
    doc.operations
      .filterIsInstance<RcNamedVariable>()
      .filter { it.type == RcNamedVariable.STRING_TYPE }
      .forEach { v -> named[v.name]?.let { textOf[v.id] = it } }
    val drawn =
      doc.operations.mapNotNull {
        when (it) {
          is RcDrawBitmapFontTextRun -> it.bitmapFontId to it.textId
          is RcDrawBitmapTextAnchored -> it.bitmapFontId to it.textId
          else -> null
        }
      }
    var unknown = 0
    val needed = mutableListOf<Int>()
    for (f in fonts) {
      val texts = drawn.filter { it.first == f.fontId }.map { it.second }
      val chars =
        texts
          .flatMap { id ->
            textOf[id]?.toList()
              ?: run {
                unknown++
                f.glyphs.flatMap { it.chars.toList() }
              }
          }
          .toSet()
      needed +=
        f.glyphs.filter { g -> g.bitmapId != -1 && g.chars.all { it in chars } }.map { it.bitmapId }
    }
    println(
      "  only what's on screen now ($unknown computed texts kept whole): ${sizes(needed.distinct())}",
    )
    val neededMasks = needed.distinct().filter { it in maskIds }
    println("  on screen, as white masks: ${sizes(neededMasks)}")

    original?.let { o ->
      val styles =
        o.operations
          .filterIsInstance<RcTextLayout>()
          .groupingBy { t ->
            val size =
              if (t.fontSize.isNaNEncoded) "var${t.fontSize.referencedId}"
              else "%.1f".format(t.fontSize.value)
            val weight = if (t.fontWeight.isNaNEncoded) "var" else "%.0f".format(t.fontWeight.value)
            val colour =
              if ((t.flags and 1) != 0) "colour id ${t.color}" else "#%08x".format(t.color)
            "size $size weight $weight $colour"
          }
          .eachCount()
      println("  text styles in the original (${styles.size}):")
      styles.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("    $v × $k") }
      val faces =
        o.operations
          .filterIsInstance<RcTextLayout>()
          .map { "${it.fontSize.bits}/${it.fontWeight.bits}" }
          .toSet()
      println("  sizes × weights alone: ${faces.size}")
    }
    sample(doc, fonts, ::alphaOf, out)
  }

  /** Sample lines from the two fonts with the most glyphs, at 8, 4, 2 and 1 bits, 3x. */
  private fun sample(
    doc: RcDocument,
    fonts: List<RcBitmapFontData>,
    alphaOf: (Int) -> Array<IntArray>?,
    out: File,
  ) {
    val text = "Home download 583.56 Mbit/s"
    val picks =
      fonts
        .sortedByDescending { f -> f.glyphs.maxOfOrNull { it.bitmapHeight.toInt() } ?: 0 }
        .distinctBy { f -> f.glyphs.maxOfOrNull { it.bitmapHeight.toInt() } }
        .let { listOf(it.first(), it[it.size / 2], it.last()) }
    val rows = mutableListOf<IntArray>()
    var width = 0
    val lines = mutableListOf<Array<IntArray>>()
    for (f in picks) for (bits in listOf(8, 4, 2, 1)) {
      val levels = 1 shl bits
      var x = 4
      val h = (f.glyphs.maxOfOrNull { it.bitmapHeight + it.marginTop + it.marginBottom } ?: 10) + 8
      val canvas = Array(h) { IntArray(600) }
      for (c in text) {
        val g = f.glyphs.firstOrNull { it.chars == c.toString() } ?: continue
        x += g.marginLeft
        alphaOf(g.bitmapId)?.let { a ->
          for (yy in a.indices) for (xx in a[yy].indices) {
            val q = quantize(a[yy][xx], levels) * 255 / (levels - 1)
            val cy = 4 + g.marginTop + yy
            val cx = x + xx
            if (cy in canvas.indices && cx in 0 until 600) canvas[cy][cx] = maxOf(canvas[cy][cx], q)
          }
        }
        x += g.bitmapWidth + g.marginRight
      }
      width = maxOf(width, x + 4)
      lines += canvas
    }
    val scale = 3
    val totalH = lines.sumOf { it.size } * scale
    val image = BufferedImage(width * scale, totalH, BufferedImage.TYPE_INT_RGB)
    var y0 = 0
    for (canvas in lines) {
      for (y in canvas.indices) for (x in 0 until width) {
        val a = canvas[y][x]
        val bg = 0x1c
        val v = (bg * (255 - a) + 0xee * a) / 255
        val rgb = (v shl 16) or (v shl 8) or v
        for (dy in 0 until scale) for (dx in 0 until scale) image.setRGB(
          x * scale + dx,
          y0 + y * scale + dy,
          rgb,
        )
      }
      y0 += canvas.size * scale
    }
    ImageIO.write(image, "png", out)
  }

  /**
   * `WALL_GLYPH_FONT=<file>:<fontId>`: that font's glyphs, each over its characters, as a strip.
   */
  @Test
  fun exportOneFont() {
    val spec = System.getenv("WALL_GLYPH_FONT") ?: return
    val file = File(spec.substringBeforeLast(':'))
    val fontId = spec.substringAfterLast(':').toInt()
    val doc = RcDocumentCodec.decode(file.readBytes(), RcWireLimits())
    val bitmaps = doc.operations.filterIsInstance<RcBitmapData>().associateBy { it.imageId }
    val font = doc.operations.filterIsInstance<RcBitmapFontData>().first { it.fontId == fontId }
    val glyphs = font.glyphs.filter { it.chars in listOf("H", "O", "o", "n", " ", "e", "m") }
    val image = BufferedImage(glyphs.size * 30, 40, BufferedImage.TYPE_INT_RGB)
    val g2 = image.createGraphics()
    glyphs.forEachIndexed { i, glyph ->
      println(
        "glyph '${glyph.chars}' bitmap ${glyph.bitmapId} ${glyph.bitmapWidth}x${glyph.bitmapHeight}",
      )
      g2.drawString(glyph.chars, i * 30 + 2, 38)
      val png =
        bitmaps[glyph.bitmapId]?.let { ImageIO.read(it.data.inputStream()) }
          ?: return@forEachIndexed
      g2.drawImage(png, i * 30 + 2, 2, null)
    }
    ImageIO.write(image, "png", File(file.parentFile, "font$fontId.png"))
  }
}
