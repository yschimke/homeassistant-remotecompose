package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBitmapFontData
import ee.schimke.ha.client.design.glyphs.GlyphPack
import ee.schimke.ha.client.design.glyphs.GlyphPackCodec
import ee.schimke.ha.rc.toBitmapFont
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun GlyphPack.Glyph.glyphRows(): List<ByteArray> =
  List(height) { y -> coverage.copyOfRange(y * width, (y + 1) * width) }

class GlyphRangesTest {
  private fun ApplicationTestBuilder.serve() = routing {
    val fonts = CardFonts.fromClasspath()
    cardFontRoutes(API_BASE, fonts)
    glyphRangeRoutes(API_BASE, GlyphRanges(ServerText(fonts)))
  }

  private fun chars(text: String) = URLEncoder.encode(text, "UTF-8")

  @Test
  fun aFacesGlyphsComeAsAPackOnOneBaseline() = testApplication {
    serve()
    val response =
      client.get("$API_BASE/fonts/glyphs?family=Roboto&size=15&density=1&chars=${chars("Hg 0")}")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(
      GlyphPack.CONTENT_TYPE,
      response.headers[HttpHeaders.ContentType]?.substringBefore(';'),
    )
    assertEquals("4", response.headers["X-Glyphs"])
    val pack = assertNotNull(GlyphPackCodec.decode(response.bodyAsBytes()))
    assertEquals("roboto 15 @1 w400", pack.key)
    assertEquals(listOf(" ", "0", "H", "g"), pack.glyphs.map { it.chars })
    for (glyph in pack.glyphs) {
      assertEquals(
        pack.lineHeight,
        glyph.marginTop + glyph.height + glyph.marginBottom,
        glyph.chars,
      )
      assertTrue(glyph.advance > 0, "${glyph.chars} advances")
    }
    val h = pack.glyphs.single { it.chars == "H" }
    assertTrue(h.coverage.any { it.toInt() and 0xff == 255 }, "an H covers whole pixels")
    assertTrue(
      pack.glyphs.single { it.chars == " " }.coverage.all { it.toInt() == 0 },
      "a space none",
    )
    // The ink's last row, in the cell: an image is its line's cell, descent included.
    fun GlyphPack.Glyph.inkBottom() =
      glyphRows().indexOfLast { row -> row.any { it.toInt() and 0xff > 64 } } + marginTop
    val g = pack.glyphs.single { it.chars == "g" }
    assertTrue(g.inkBottom() > pack.baseline + 1, "a g descends below the baseline")
    assertTrue(h.inkBottom() in pack.baseline - 2..pack.baseline, "an H stands on it")
  }

  @Test
  fun aSmallerDepthDrawsTheSameGlyphsSmaller() = testApplication {
    serve()
    val query = "family=roboto&size=22&ranges=0x41-0x5a"
    val eight = client.get("$API_BASE/fonts/glyphs?$query").bodyAsBytes()
    val two = client.get("$API_BASE/fonts/glyphs?$query&bits=2").bodyAsBytes()
    assertTrue(two.size < eight.size / 2, "${two.size} bytes at 2 bits, ${eight.size} at 8")
    val a = GlyphPackCodec.decode(eight)!!
    val b = GlyphPackCodec.decode(two)!!
    assertEquals(26, a.glyphs.size)
    assertEquals(a.glyphs.map { it.advance }, b.glyphs.map { it.advance }, "the same metrics")
  }

  @Test
  fun aDensityDrawsTheSizeInThatManyDevicePixels() = testApplication {
    serve()
    fun line(density: Int) = runCatching {
      kotlinx.coroutines.runBlocking {
        GlyphPackCodec.decode(
            client
              .get("$API_BASE/fonts/glyphs?family=roboto&size=14&density=$density&chars=Hg")
              .bodyAsBytes(),
          )!!
          .lineHeight
      }
    }
      .getOrThrow()
    val one = line(1)
    val two = line(2)
    assertTrue(two in one * 2 - 2..one * 2 + 2, "line $one at density 1, $two at 2")
  }

  @Test
  fun aRepeatedRequestIsNotModified() = testApplication {
    serve()
    val url = "$API_BASE/fonts/glyphs?family=roboto&size=15&chars=abc"
    val etag = assertNotNull(client.get(url).headers[HttpHeaders.ETag])
    val again = client.get(url) { header(HttpHeaders.IfNoneMatch, etag) }
    assertEquals(HttpStatusCode.NotModified, again.status)
  }

  @Test
  fun kerningIsMeasuredWhenAskedFor() = testApplication {
    serve()
    val pack =
      GlyphPackCodec.decode(
        client
          .get("$API_BASE/fonts/glyphs?family=roboto&size=48&chars=AVT.&kerning=1")
          .bodyAsBytes(),
      )!!
    assertTrue(pack.kerning.values.all { it != 0 })
    assertTrue(pack.kerning.keys.all { it.length == 2 })
  }

  @Test
  fun anUnknownFamilyOrABadRequestIsRefused() = testApplication {
    serve()
    assertEquals(
      HttpStatusCode.NotFound,
      client.get("$API_BASE/fonts/glyphs?family=google:Lobster%20Two&size=14&chars=a").status,
    )
    for (query in
      listOf(
        "size=14&chars=a",
        "family=roboto&chars=a",
        "family=roboto&size=1000&chars=a",
        "family=roboto&size=14",
        "family=roboto&size=14&chars=a&bits=3",
        "family=roboto&size=14&ranges=0x7e-0x20",
      )) {
      assertEquals(
        HttpStatusCode.BadRequest,
        client.get("$API_BASE/fonts/glyphs?$query").status,
        query,
      )
    }
  }

  @Test
  fun aPackIsABitmapFontOfRaw8Masks() = testApplication {
    serve()
    val pack =
      GlyphPackCodec.decode(
        client.get("$API_BASE/fonts/glyphs?family=roboto&size=15&chars=ab").bodyAsBytes(),
      )!!
    val operations = pack.toBitmapFont(fontId = 900, firstBitmapId = 100)
    val bitmaps = operations.filterIsInstance<RcBitmapData>()
    assertEquals(listOf(100, 101), bitmaps.map { it.imageId })
    assertTrue(
      bitmaps.all { it.type == RcBitmapData.TYPE_RAW8 && it.data.size == it.width * it.height },
    )
    val font = operations.filterIsInstance<RcBitmapFontData>().single()
    assertEquals(900, font.fontId)
    assertEquals(listOf("a", "b"), font.glyphs.map { it.chars })
    assertEquals(
      pack.glyphs.map { it.advance },
      font.glyphs.map { it.marginLeft + it.bitmapWidth + it.marginRight },
    )
  }

  /** A local tool: `GLYPH_OUT=<dir>` writes the wall's Roboto packs there, to measure. */
  @Test
  fun writeWallPacks() = testApplication {
    val dir = System.getenv("GLYPH_OUT")?.let { java.io.File(it) } ?: return@testApplication
    serve()
    dir.mkdirs()
    val chars =
      chars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,:;-+/%°()'&!?")
    for ((size, weight) in listOf(13 to 500, 15 to 400, 22 to 500)) {
      val bytes =
        client
          .get("$API_BASE/fonts/glyphs?family=roboto&size=$size&weight=$weight&chars=$chars")
          .bodyAsBytes()
      java.io.File(dir, "roboto-$size-$weight.rcgp").writeBytes(bytes)
    }
    java.io
      .File(dir, "roboto-400.ttf")
      .writeBytes(
        client.get("$API_BASE/fonts/glyphs?family=roboto&format=font&chars=$chars").bodyAsBytes(),
      )
  }

  @Test
  fun aFontIsTheFaceCutToTheCharactersAsked() = testApplication {
    serve()
    val response =
      client.get("$API_BASE/fonts/glyphs?family=roboto&format=font&weight=700&chars=Ab1")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("font/ttf", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
    assertEquals("3", response.headers["X-Glyphs"])
    val bytes = response.bodyAsBytes()
    val whole =
      GlyphRangesTest::class
        .java
        .classLoader
        .getResourceAsStream("wall-fonts/Roboto-Bold.ttf")!!
        .use { it.readBytes() }
    assertTrue(bytes.size < whole.size / 20, "${bytes.size} bytes of ${whole.size}")
    org.apache.fontbox.ttf
      .TTFParser()
      .parse(org.apache.pdfbox.io.RandomAccessReadBuffer(bytes))
      .use {
        val cmap = it.unicodeCmapLookup
        assertTrue("Ab1".all { c -> cmap.getGlyphId(c.code) != 0 }, "the characters asked")
        assertEquals(0, cmap.getGlyphId('Z'.code), "and no others")
        assertTrue(
          it.name.contains("Bold", ignoreCase = true),
          "the face nearest weight 700: ${it.name}",
        )
      }
    val etag = assertNotNull(response.headers[HttpHeaders.ETag])
    assertEquals(
      HttpStatusCode.NotModified,
      client
        .get("$API_BASE/fonts/glyphs?family=roboto&format=font&weight=700&chars=Ab1") {
          header(HttpHeaders.IfNoneMatch, etag)
        }
        .status,
    )
  }

  @Test
  fun theFontRoutesStillServeTheirFiles() = testApplication {
    serve()
    assertEquals(HttpStatusCode.OK, client.get("$API_BASE/fonts/fonts.json").status)
  }
}
