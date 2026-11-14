package ee.schimke.ha.client.design.glyphs

/**
 * One face at one size as glyph images: what `GET <base>/fonts/glyphs` returns
 * (`docs/architecture/glyph-range-fonts.md`). Each glyph's metrics are a RemoteCompose
 * `BitmapFontData` glyph's: its advance is `marginLeft + width + marginRight`, every glyph's cell
 * is [lineHeight] tall, and `marginTop` sets each on the shared [baseline]. Coverage is a white
 * mask the player tints, a byte a pixel here whatever depth it travelled at.
 */
class GlyphPack(
  val family: String,
  /** The text's size as a document writes it (dp). */
  val size: Float,
  /** Device pixels per dp the glyphs are drawn for. */
  val density: Float,
  val weight: Int,
  val italic: Boolean,
  /** Every glyph's cell, top to bottom, in device pixels. */
  val lineHeight: Int,
  /** The baseline, from the cell's top. */
  val baseline: Int,
  val glyphs: List<Glyph>,
  /** Adjustments for pairs of glyphs set side by side, by their two `chars` joined. */
  val kerning: Map<String, Int> = emptyMap(),
) {
  class Glyph(
    val chars: String,
    val marginLeft: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
    val width: Int,
    val height: Int,
    /** [height] rows of [width] bytes: 0 clear, 255 covered. */
    val coverage: ByteArray,
    /**
     * A colour glyph's pixels (an emoji), premultiplied ARGB, [height] rows of [width]: drawn as
     * they are, not tinted. Null for a coverage glyph, which the text's colour tints.
     */
    val argb: IntArray? = null,
  ) {
    val advance: Int
      get() = marginLeft + width + marginRight
  }

  /** The face and size, as a store keys it: `roboto 14 @2 w400`. */
  val key: String
    get() = key(family, size, density, weight, italic)

  /** The characters the pack has glyphs for. */
  val chars: Set<String>
    get() = glyphs.mapTo(mutableSetOf()) { it.chars }

  /**
   * This pack with [other]'s glyphs added (its own where both have one), every glyph set on the
   * lower of the two baselines in a cell tall enough for both: two requests for the same face
   * measure their cells by the glyphs they drew.
   */
  fun merge(other: GlyphPack): GlyphPack {
    require(other.key == key) { "${other.key} is another face or size than $key" }
    val baseline = maxOf(baseline, other.baseline)
    val below = maxOf(lineHeight - this.baseline, other.lineHeight - other.baseline)
    val lineHeight = baseline + below
    fun Glyph.rebased(from: Int): Glyph {
      val top = marginTop + baseline - from
      return Glyph(
        chars,
        marginLeft,
        top,
        marginRight,
        lineHeight - top - height,
        width,
        height,
        coverage,
        argb,
      )
    }
    val mine = glyphs.associateBy { it.chars }
    val merged =
      glyphs.map { it.rebased(this.baseline) } +
        other.glyphs.filter { it.chars !in mine }.map { it.rebased(other.baseline) }
    return GlyphPack(
      family,
      size,
      density,
      weight,
      italic,
      lineHeight,
      baseline,
      merged,
      other.kerning + kerning,
    )
  }

  companion object {
    const val CONTENT_TYPE: String = "application/vnd.ha.glyphs"

    fun key(family: String, size: Float, density: Float, weight: Int, italic: Boolean): String =
      "${family.lowercase().removePrefix("google:")} ${size.compact()} @${density.compact()} " +
        "w$weight${if (italic) " italic" else ""}"

    private fun Float.compact(): String =
      if (this == toInt().toFloat()) toInt().toString() else toString()
  }
}

/**
 * The glyph pack's wire form: big-endian, every field fixed or length-prefixed, so a client in any
 * language reads it without a library. Coverage travels at [GlyphPackCodec.encode]'s depth (8, 4, 2
 * or 1 bits, rows padded to a byte, high bits first) and is read back to a byte a pixel. At 32 bits
 * a pack is colour: each pixel is premultiplied ARGB, big-endian, the glyph drawn as it is (emoji).
 */
object GlyphPackCodec {
  private val MAGIC =
    byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'P'.code.toByte())
  private const val VERSION = 1
  private const val FLAG_KERNING = 1

  /** The depths coverage can travel at; [COLOR_BITS] for colour glyphs. */
  val BITS: Set<Int> = setOf(8, 4, 2, 1, COLOR_BITS)

  /** A colour pack's depth: premultiplied ARGB a pixel. */
  const val COLOR_BITS: Int = 32

  fun encode(pack: GlyphPack, bits: Int = 8): ByteArray {
    require(bits in BITS) { "bits must be one of $BITS" }
    val out = Writer()
    out.bytes(MAGIC)
    out.u8(VERSION)
    out.u8(bits)
    out.u16(if (pack.kerning.isEmpty()) 0 else FLAG_KERNING)
    out.string16(pack.family)
    out.f32(pack.size)
    out.f32(pack.density)
    out.u16(pack.weight)
    out.u8(if (pack.italic) 1 else 0)
    out.i16(pack.lineHeight)
    out.i16(pack.baseline)
    out.u16(pack.glyphs.size)
    for (glyph in pack.glyphs) {
      out.string8(glyph.chars)
      out.i16(glyph.marginLeft)
      out.i16(glyph.marginTop)
      out.i16(glyph.marginRight)
      out.i16(glyph.marginBottom)
      out.u16(glyph.width)
      out.u16(glyph.height)
      if (bits == COLOR_BITS) {
        for (i in 0 until glyph.width * glyph.height) {
          // A coverage glyph in a colour pack is white at its coverage.
          val a = glyph.coverage[i].toInt() and 0xff
          val argb = glyph.argb?.get(i) ?: ((a shl 24) or (a shl 16) or (a shl 8) or a)
          out.u16(argb ushr 16)
          out.u16(argb and 0xffff)
        }
      } else {
        out.bytes(pack(glyph.coverage, glyph.width, glyph.height, bits))
      }
    }
    if (pack.kerning.isNotEmpty()) {
      out.u16(pack.kerning.size)
      for ((pair, adjustment) in pack.kerning) {
        out.string8(pair)
        out.i16(adjustment)
      }
    }
    return out.toByteArray()
  }

  /** [bytes] as a pack, or null if they aren't one. */
  fun decode(bytes: ByteArray): GlyphPack? = runCatching {
    val input = Reader(bytes)
    if (!input.bytes(4).contentEquals(MAGIC)) return null
    if (input.u8() != VERSION) return null
    val bits = input.u8().takeIf { it in BITS } ?: return null
    val flags = input.u16()
    val family = input.string16()
    val size = input.f32()
    val density = input.f32()
    val weight = input.u16()
    val italic = input.u8() != 0
    val lineHeight = input.i16()
    val baseline = input.i16()
    val glyphs =
      List(input.u16()) {
        val chars = input.string8()
        val left = input.i16()
        val top = input.i16()
        val right = input.i16()
        val bottom = input.i16()
        val width = input.u16()
        val height = input.u16()
        if (bits == COLOR_BITS) {
          val argb = IntArray(width * height) { (input.u16() shl 16) or input.u16() }
          GlyphPack.Glyph(
            chars,
            left,
            top,
            right,
            bottom,
            width,
            height,
            ByteArray(argb.size) { (argb[it] ushr 24).toByte() },
            argb,
          )
        } else {
          val packed = input.bytes(rowBytes(width, bits) * height)
          GlyphPack.Glyph(
            chars,
            left,
            top,
            right,
            bottom,
            width,
            height,
            unpack(packed, width, height, bits),
          )
        }
      }
    val kerning =
      if (flags and FLAG_KERNING != 0) {
        List(input.u16()) { input.string8() to input.i16() }.toMap()
      } else emptyMap()
    if (!input.done) return null
    GlyphPack(family, size, density, weight, italic, lineHeight, baseline, glyphs, kerning)
  }
    .getOrNull()

  private fun rowBytes(width: Int, bits: Int) = (width * bits + 7) / 8

  private fun pack(coverage: ByteArray, width: Int, height: Int, bits: Int): ByteArray {
    if (bits == 8) return coverage
    val levels = (1 shl bits) - 1
    val row = rowBytes(width, bits)
    val out = ByteArray(row * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val a = coverage[y * width + x].toInt() and 0xff
        val q = (a * levels + 127) / 255
        val bit = x * bits
        val index = y * row + bit / 8
        out[index] = (out[index].toInt() or (q shl (8 - bits - bit % 8))).toByte()
      }
    }
    return out
  }

  private fun unpack(packed: ByteArray, width: Int, height: Int, bits: Int): ByteArray {
    if (bits == 8) return packed
    val levels = (1 shl bits) - 1
    val row = rowBytes(width, bits)
    val out = ByteArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val bit = x * bits
        val q = (packed[y * row + bit / 8].toInt() shr (8 - bits - bit % 8)) and levels
        out[y * width + x] = (q * 255 / levels).toByte()
      }
    }
    return out
  }

  private class Writer {
    private val out = ArrayList<Byte>()

    fun bytes(b: ByteArray) = b.forEach { out += it }

    fun u8(v: Int) {
      require(v in 0..0xff) { "$v doesn't fit a byte" }
      out += v.toByte()
    }

    fun u16(v: Int) {
      require(v in 0..0xffff) { "$v doesn't fit 16 bits" }
      out += (v shr 8).toByte()
      out += v.toByte()
    }

    fun i16(v: Int) {
      require(v in Short.MIN_VALUE..Short.MAX_VALUE) { "$v doesn't fit 16 bits" }
      u16(v and 0xffff)
    }

    fun f32(v: Float) {
      val bits = v.toRawBits()
      u16(bits ushr 16)
      u16(bits and 0xffff)
    }

    fun string8(s: String) {
      val b = s.encodeToByteArray()
      u8(b.size)
      bytes(b)
    }

    fun string16(s: String) {
      val b = s.encodeToByteArray()
      u16(b.size)
      bytes(b)
    }

    fun toByteArray() = out.toByteArray()
  }

  private class Reader(private val data: ByteArray) {
    private var at = 0
    val done: Boolean
      get() = at == data.size

    fun bytes(n: Int): ByteArray {
      require(n >= 0 && at + n <= data.size) { "truncated" }
      return data.copyOfRange(at, at + n).also { at += n }
    }

    fun u8() = bytes(1)[0].toInt() and 0xff

    fun u16() = (u8() shl 8) or u8()

    fun i16() = u16().toShort().toInt()

    fun f32() = Float.fromBits((u16() shl 16) or u16())

    fun string8() = bytes(u8()).decodeToString()

    fun string16() = bytes(u16()).decodeToString()
  }
}
