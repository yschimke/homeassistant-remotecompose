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
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
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
      WidgetBitmapFonts.ALPHANUMERIC.map { it.toString() }.all { it in glyphs },
      "the live reading can show any letter, digit or common sign",
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

  private suspend fun io.ktor.server.testing.ApplicationTestBuilder.liveGlyphs(
    query: String,
  ): Pair<String?, Set<String>> {
    val response =
      client.post("$API_BASE/filters/bitmap-fonts?host=wear-widgets&$query") { setBody(readout) }
    assertEquals(HttpStatusCode.OK, response.status)
    val fonts = filtered(response.bodyAsBytes()).operations.filterIsInstance<RcBitmapFontData>()
    return response.headers["X-Bitmap-Fonts"] to
      fonts.flatMap { font -> font.glyphs.map { it.chars } }.toSet()
  }

  @Test
  fun aLocaleAddsItsLettersAndAScriptTooLargeLeavesLiveTextAsText() = testApplication {
    routing { widgetFilterRoutes(API_BASE, WidgetFilters(CardFonts.fromClasspath())) }
    val (_, german) = liveGlyphs("locale=de-DE")
    assertTrue(listOf("ä", "Ü", "ß").all { it in german }, "German's letters, both cases")

    val (chinese, _) = liveGlyphs("locale=zh-CN")
    assertEquals("converted=1; skipped=1", chinese, "the fixed clock only: the reading stays text")
  }

  @Test
  fun aBindingsValuesAreItsGlyphsExactly() = testApplication {
    routing { widgetFilterRoutes(API_BASE, WidgetFilters(CardFonts.fromClasspath())) }
    val values = java.net.URLEncoder.encode("""{"sensor.s0.state":["on","off"]}""", "UTF-8")
    val (_, glyphs) = liveGlyphs("values=$values")
    assertEquals(setOf("1", "2", ":", "3", "0", "o", "n", "f"), glyphs, "the clock's and on/off's")
  }

  /** The Android 16 launcher's text component, `TextLayout`, is drawn from glyphs too. */
  @Test
  fun aLauncherTextLayoutIsDrawnFromItsGlyphs() = testApplication {
    routing { widgetFilterRoutes(API_BASE, WidgetFilters(CardFonts.fromClasspath())) }
    fun layout(textId: Int, componentId: Int, family: Int) =
      listOf(
        RcTextLayout(
          componentId,
          -1,
          textId,
          0xff000000.toInt(),
          RcFloatWord.literal(28f),
          0,
          RcFloatWord.literal(400f),
          family,
          1,
          1,
          Int.MAX_VALUE,
        ),
        RcLayoutContent(componentId - 100),
        end,
        end,
      )
    val launcher =
      RcDocumentCodec.encode(
        RcDocument(
          RcHeader(RcVersion(1, 0, 0)),
          listOf(
            RcTextData(50, "digital7"),
            RcTextData(51, "sans-serif"),
            RcTextData(42, "12:30"),
            RcTextData(44, "Living room"),
            RcRootLayout(-1),
            RcLayoutContent(-2),
            *layout(42, -7, 50).toTypedArray(),
            *layout(44, -9, 51).toTypedArray(),
            end,
            end,
          ),
        ),
      )
    val response =
      client.post("$API_BASE/filters/bitmap-fonts?host=launcher-widgets&density=3") {
        setBody(launcher)
      }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("converted=1; skipped=0", response.headers["X-Bitmap-Fonts"])
    val document = filtered(response.bodyAsBytes())
    assertEquals(
      listOf(44),
      document.operations.filterIsInstance<RcTextLayout>().map { it.textId },
      "only the sans-serif label stays text, still a TextLayout",
    )
    assertTrue(document.operations.none { it is RcCoreText }, "a launcher reads no CoreText")
    assertEquals(
      emptyList(),
      document.validateFor(RcTargetProfiles.WIDGETS_V6).filter { it.opcode != null },
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
      HttpStatusCode.BadRequest,
      client.post("$path?host=wear-widgets&values=nope") { setBody(readout) }.status,
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

  /**
   * The wall panel's text is in the default family: the `wall` host draws it in the panel's Roboto,
   * from glyphs, where a widget host leaves it as text.
   */
  @Test
  fun aWallsDefaultFamilyTextIsDrawnInRobotoFromItsGlyphs() = testApplication {
    routing { widgetFilterRoutes(API_BASE, WidgetFilters(CardFonts.fromClasspath())) }
    val document =
      RcDocumentCodec.encode(
        RcDocument(
          RcHeader(RcVersion(1, 1, 0)),
          listOf(
            RcTextData(42, "Kitchen"),
            RcRootLayout(-1),
            RcLayoutContent(-2),
            *text(42, -7, -1).toTypedArray(),
            end,
            end,
          ),
        ),
      )
    for ((host, converted) in listOf("wall" to 1, "wear-widgets" to 0)) {
      val response =
        client.post("$API_BASE/filters/bitmap-fonts?host=$host&density=1") { setBody(document) }
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("converted=$converted; skipped=0", response.headers["X-Bitmap-Fonts"], host)
    }
    val wall =
      filtered(
        client
          .post("$API_BASE/filters/bitmap-fonts?host=wall&density=1") { setBody(document) }
          .bodyAsBytes(),
      )
    val glyphs =
      wall.operations.filterIsInstance<RcBitmapFontData>().flatMap { f ->
        f.glyphs.map { it.chars }
      }
    assertEquals(setOf("K", "i", "t", "c", "h", "e", "n"), glyphs.toSet())
  }

  /**
   * A local tool, not a check: `WALL_FILTER_DIR=<dir>` runs the `wall` filter over every `.rc` in
   * it and writes each beside it as `.wall.rc`, for a panel player to compare.
   */
  @Test
  fun filterLocalWallDocuments() = testApplication {
    val dir = System.getenv("WALL_FILTER_DIR")?.let { java.io.File(it) } ?: return@testApplication
    routing { widgetFilterRoutes(API_BASE, WidgetFilters(CardFonts.fromClasspath())) }
    val maxLength = System.getenv("WALL_FILTER_MAX_LENGTH") ?: "16"
    for (file in
      dir.listFiles().orEmpty().filter {
        it.name.endsWith(".rc") && !it.name.endsWith(".wall.rc")
      }) {
      val response =
        client.post("$API_BASE/filters/bitmap-fonts?host=wall&density=1&maxLength=$maxLength") {
          setBody(file.readBytes())
        }
      val out = java.io.File(dir, file.name.removeSuffix(".rc") + ".wall.rc")
      out.writeBytes(response.bodyAsBytes())
      println(
        "wall filter ${file.name}: ${response.headers["X-Bitmap-Fonts"]}, ${file.length()} -> ${out.length()} bytes",
      )
    }
  }
}
