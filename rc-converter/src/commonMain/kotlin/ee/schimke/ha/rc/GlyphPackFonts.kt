package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBitmapFontData
import ee.schimke.composeai.rcplayer.protocol.RcBitmapFontGlyph
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.ha.client.design.glyphs.GlyphPack

/**
 * [this] pack as a RemoteCompose bitmap font: a `RAW8` bitmap a glyph (its coverage, a white mask
 * the canvas's paint tints) and the `BitmapFontData` naming them, the font's id [fontId] and its
 * glyphs' from [firstBitmapId] up. For a host that plays bitmap fonts and fetched a face's glyphs
 * by range (`GlyphCache`) rather than finding them in the document: the metrics are the font's as
 * they are.
 */
fun GlyphPack.toBitmapFont(fontId: Int, firstBitmapId: Int): List<RcOperation> {
  // A glyph with no ink (a space) has no image: bitmap `-1`, its advance in its margin.
  fun GlyphPack.Glyph.inked() = width > 0 && height > 0
  val bitmaps = glyphs.mapIndexedNotNull { index, glyph ->
    if (!glyph.inked()) return@mapIndexedNotNull null
    RcBitmapData(
      imageId = firstBitmapId + index,
      width = glyph.width,
      height = glyph.height,
      type = RcBitmapData.TYPE_RAW8,
      encoding = RcBitmapData.ENCODING_INLINE,
      data = glyph.coverage,
    )
  }
  val font =
    RcBitmapFontData(
      fontId,
      glyphs.mapIndexed { index, glyph ->
        RcBitmapFontGlyph(
          chars = glyph.chars,
          bitmapId = if (glyph.inked()) firstBitmapId + index else -1,
          marginLeft = glyph.marginLeft.toShort(),
          marginTop = glyph.marginTop.toShort(),
          marginRight = glyph.marginRight.toShort(),
          marginBottom = glyph.marginBottom.toShort(),
          bitmapWidth = glyph.width.toShort(),
          bitmapHeight = glyph.height.toShort(),
        )
      },
      kerning.mapValues { it.value.toShort() },
    )
  return bitmaps + font
}
