package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import java.io.ByteArrayOutputStream
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TTFSubsetter
import org.apache.pdfbox.io.RandomAccessReadBuffer

/**
 * A face cut to the characters a client asks for ([GlyphRanges], `format=font`): its outlines, one
 * download for every size, for a client that rasterises text well (a phone, a browser) or once into
 * a glyph cache of its own. FontBox's subsetter keeps the outlines, metrics, character map and
 * TrueType hinting, and drops OpenType layout: no ligatures, and no kerning (it is in `GPOS`).
 */
internal object FontSubsets {
  class Subset(val bytes: ByteArray, val glyphs: Int) {
    val sha: String = Sha256.hex(bytes)
  }

  /** [face] (TrueType) cut to [chars], or null if it isn't a face FontBox can read. */
  fun subset(face: ByteArray, chars: Set<String>): Subset? = runCatching {
    TTFParser().parse(RandomAccessReadBuffer(face)).use { font ->
      val codePoints = chars.map { it.codePointAt(0) }
      val cmap = font.unicodeCmapLookup
      val subsetter = TTFSubsetter(font, KEPT_TABLES)
      codePoints.forEach(subsetter::add)
      val out = ByteArrayOutputStream()
      subsetter.writeToStream(out)
      Subset(out.toByteArray(), codePoints.count { cmap.getGlyphId(it) != 0 })
    }
  }
    .getOrNull()

  /** Besides what TrueType needs to draw, the names (`name`) and the vertical metrics (`OS/2`). */
  private val KEPT_TABLES =
    listOf(
      "head",
      "hhea",
      "loca",
      "maxp",
      "cvt ",
      "prep",
      "glyf",
      "hmtx",
      "fpgm",
      "OS/2",
      "name",
      "post",
      "cmap",
    )
}
