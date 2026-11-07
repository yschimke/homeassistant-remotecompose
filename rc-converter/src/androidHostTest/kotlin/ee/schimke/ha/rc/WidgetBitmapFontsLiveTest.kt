package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.edit.RcBitmapFontGlyphs
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcIdList
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextFromFloat
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextLookupInt
import ee.schimke.composeai.rcplayer.protocol.RcTextMerge
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A text the document computes says what it can show: a clock's time, two two-digit numbers and a
 * colon, gets those glyphs and a slot of five, not the default set's twelve.
 */
class WidgetBitmapFontsLiveTest {
  private fun number(id: Int, digits: Int) =
    RcTextFromFloat(id, RcFloatWord.literal(0f), digits, 0, 12)

  private fun shown(textId: Int) =
    RcTextLayout(
      -7,
      -1,
      textId,
      0xff000000.toInt(),
      RcFloatWord.literal(28f),
      0,
      RcFloatWord.literal(400f),
      50,
      1,
      1,
      Int.MAX_VALUE,
    )

  private fun glyphs(vararg operations: ee.schimke.composeai.rcplayer.protocol.RcOperation) =
    WidgetBitmapFonts.liveGlyphs(RcDocument(RcHeader(RcVersion(1, 0, 0)), operations.toList()))(-7)

  @Test
  fun aClocksTimeGetsItsDigitsAndColonInASlotOfFive() {
    val glyphs =
      glyphs(
        number(43, 2),
        RcTextData(47, ":"),
        RcTextMerge(48, 43, 47),
        number(44, 2),
        RcTextMerge(49, 48, 44),
        shown(49),
      )
    assertEquals(5, glyphs?.maxLength)
    assertEquals("0123456789-., :".toSet(), glyphs?.chars?.toSet())
  }

  @Test
  fun aLookupAmongFormatsIsBoundedByTheFormatsThatAreBounded() {
    // 12 or 24 hours: unpadded ("as many digits as it takes") or two digits.
    val glyphs =
      glyphs(
        number(41, 255),
        number(42, 2),
        RcIdList(60, listOf(41, 42)),
        RcTextLookupInt(43, 60, 45),
        RcTextData(47, ":"),
        RcTextMerge(48, 43, 47),
        number(44, 2),
        RcTextMerge(49, 48, 44),
        shown(49),
      )
    assertEquals(5, glyphs?.maxLength)
  }

  @Test
  fun anUnboundedNumberGetsTheDefaultSet() {
    assertEquals(
      RcBitmapFontGlyphs(WidgetBitmapFonts.ALPHANUMERIC, WidgetBitmapFonts.DEFAULT_LIVE_LENGTH),
      glyphs(number(43, 255), shown(43)),
    )
  }
}
