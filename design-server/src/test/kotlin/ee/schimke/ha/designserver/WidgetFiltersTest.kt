package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcBitmapFontData
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty.FloatValue
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty.IntValue
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.rc.WidgetBitmapFonts
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetFiltersTest {
  private val end: RcOperation = RcNoArg(RcOpcodes.CONTAINER_END)

  private fun text(textId: Int, componentId: Int, family: Int) =
    listOf(
      RcCoreText(
        textId,
        listOf(
          IntValue(1, componentId),
          FloatValue(5, RcFloatWord.literal(28f)),
          IntValue(8, family),
        ),
      ),
      RcLayoutContent(componentId - 100),
      end,
      end,
    )

  /** A Rugged readout: a fixed clock and a reading the card fills in, both in Digital-7. */
  private val readout: ByteArray =
    RcDocumentCodec.encode(
      RcDocument(
        RcHeader(RcVersion(1, 1, 0)),
        listOf(
          RcTextData(50, "digital7"),
          RcTextData(51, "sans-serif"),
          RcTextData(42, "12:30"),
          RcTextData(43, "21.5°C"),
          RcNamedVariable(43, RcNamedVariable.STRING_TYPE, "USER:sensor.s0.state"),
          RcTextData(44, "Living room"),
          RcRootLayout(-1),
          RcLayoutContent(-2),
          *text(42, -7, 50).toTypedArray(),
          *text(43, -8, 50).toTypedArray(),
          *text(44, -9, 51).toTypedArray(),
          end,
          end,
        ),
      ),
    )

  private fun filtered(bytes: ByteArray) = RcDocumentCodec.decode(bytes, RcWireLimits())

  @Test
  fun aWearWidgetsDigital7IsDrawnFromItsGlyphsAndEverythingElseLeft() = testApplication {
    routing { widgetFilterRoutes(API_BASE, WidgetFilters(CardFonts.fromClasspath())) }
    val response =
      client.post("$API_BASE/filters/bitmap-fonts?host=wear-widgets&density=2") { setBody(readout) }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("converted=2; skipped=0", response.headers["X-Bitmap-Fonts"])
    val bytes = response.bodyAsBytes()
    // A face at one size, every live glyph: about 27 KB, inside a widget's 50 KB budget.
    assertTrue(bytes.size < 40_000, "${bytes.size} bytes for one face at one size")
    val document = filtered(bytes)
    val texts = document.operations.filterIsInstance<RcCoreText>()
    assertEquals(listOf(44), texts.map { it.textId }, "only the sans-serif label stays text")
    val fonts = document.operations.filterIsInstance<RcBitmapFontData>()
    val glyphs = fonts.flatMap { font -> font.glyphs.map { it.chars } }.toSet()
    assertTrue(
      WidgetBitmapFonts.LIVE_CHARS.map { it.toString() }.all { it in glyphs },
      "the live reading can show any printable character",
    )
    assertTrue(
      RcNamedVariable(43, RcNamedVariable.STRING_TYPE, "USER:sensor.s0.state") in
        document.operations,
      "the card still fills the reading in",
    )
    assertEquals(
      emptyList(),
      document.validateFor(RcTargetProfiles.WEAR_WIDGETS).filter { it.opcode != null },
    )
  }

  @Test
  fun aBadRequestSaysWhy() = testApplication {
    routing { widgetFilterRoutes(API_BASE, WidgetFilters(CardFonts.fromClasspath())) }
    val path = "$API_BASE/filters/bitmap-fonts"
    assertEquals(
      HttpStatusCode.BadRequest,
      client.post("$path?host=fridge") { setBody(readout) }.status,
    )
    assertEquals(
      HttpStatusCode.BadRequest,
      client.post("$path?host=wear-widgets&density=40") { setBody(readout) }.status,
    )
    assertEquals(
      HttpStatusCode.UnprocessableEntity,
      client.post("$path?host=launcher-widgets") { setBody(byteArrayOf(1, 2, 3)) }.status,
    )
    assertEquals(
      HttpStatusCode.PayloadTooLarge,
      client
        .post("$path?host=wear-widgets") { setBody(ByteArray(WidgetFilters.MAX_BYTES + 1)) }
        .status,
    )
  }
}
