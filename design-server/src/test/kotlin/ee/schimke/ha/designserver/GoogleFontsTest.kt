package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.glyphs.GlyphPackCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GoogleFontsTest {
  /** A real face to stand in for Google's file: the server's own Roboto Bold. */
  private val ttf: ByteArray =
    GoogleFontsTest::class
      .java
      .classLoader
      .getResourceAsStream("wall-fonts/Roboto-Bold.ttf")!!
      .use { it.readBytes() }

  private fun css(weight: Int, url: String = "https://fonts.gstatic.com/s/lobstertwo/v22/a.ttf") =
    """
    @font-face {
      font-family: 'Lobster Two';
      font-style: normal;
      font-weight: $weight;
      src: url($url) format('truetype');
    }
    """
      .trimIndent()

  /** Google, as far as [GoogleFonts] sees it: Lobster Two at 400 and 700, nothing else. */
  private fun google(requests: MutableList<String>, fontUrl: String? = null) =
    HttpClient(
      MockEngine { request ->
        val url = request.url.toString()
        requests += url
        when {
          request.url.host == "fonts.gstatic.com" -> respond(ttf)
          "Lobster+Two" !in url && "Lobster%20Two" !in url ->
            respond("bad family", HttpStatusCode.BadRequest)
          "wght@0,700" in url -> respond(fontUrl?.let { css(700, it) } ?: css(700))
          "wght@" in url -> respond("no such axis value", HttpStatusCode.BadRequest)
          else -> respond(css(400))
        }
      },
    )

  @Test
  fun aFaceIsFetchedOnceThenKeptInMemoryAndOnDisk() = runTest {
    val requests = mutableListOf<String>()
    val dir = Files.createTempDirectory("google-fonts").toFile()
    val fonts = GoogleFonts(google(requests), dir)
    val face = assertNotNull(fonts.face("Lobster Two", 700, false))
    assertEquals(700, face.weight)
    assertTrue(face.bytes.contentEquals(ttf))
    assertEquals(2, requests.size, "the CSS, then the file")
    fonts.face("Lobster Two", 700, false)
    assertEquals(2, requests.size, "from memory")

    val restarted = GoogleFonts(google(requests), dir)
    assertEquals(700, restarted.face("Lobster Two", 700, false)?.weight)
    assertEquals(2, requests.size, "from disk")
    dir.deleteRecursively()
  }

  @Test
  fun aWeightTheFamilyLacksIsItsDefaultFace() = runTest {
    val face = assertNotNull(GoogleFonts(google(mutableListOf())).face("Lobster Two", 300, false))
    assertEquals(400, face.weight)
  }

  @Test
  fun aFamilyGoogleDoesntHaveIsRememberedAsMissing() = runTest {
    val requests = mutableListOf<String>()
    val fonts = GoogleFonts(google(requests))
    assertNull(fonts.face("Nowhere Sans", 400, false))
    val asked = requests.size
    assertNull(fonts.face("Nowhere Sans", 400, false))
    assertEquals(asked, requests.size)
    assertNull(fonts.face("../etc/passwd", 400, false), "not a family name")
  }

  @Test
  fun aFileOffGooglesFontHostIsNotFetched() = runTest {
    val requests = mutableListOf<String>()
    val fonts = GoogleFonts(google(requests, fontUrl = "https://example.test/a.ttf"))
    assertNull(fonts.face("Lobster Two", 700, false))
    assertTrue(requests.none { "example.test" in it })
  }

  @Test
  fun theGlyphRouteDrawsAGoogleFamily() = testApplication {
    routing {
      glyphRangeRoutes(
        API_BASE,
        GlyphRanges(ServerText(CardFonts.fromClasspath()), GoogleFonts(google(mutableListOf()))),
      )
    }
    val response =
      client.get("$API_BASE/fonts/glyphs?family=google:Lobster%20Two&size=14&chars=Ab1")
    assertEquals(HttpStatusCode.OK, response.status)
    val pack = assertNotNull(GlyphPackCodec.decode(response.bodyAsBytes()))
    assertEquals(listOf("1", "A", "b"), pack.glyphs.map { it.chars })
    assertEquals("lobster two 14 @1 w400", pack.key)
  }

  @Test
  fun aGoogleFamilyComesAsAFontToo() = testApplication {
    routing {
      glyphRangeRoutes(
        API_BASE,
        GlyphRanges(ServerText(CardFonts.fromClasspath()), GoogleFonts(google(mutableListOf()))),
      )
    }
    val response =
      client.get("$API_BASE/fonts/glyphs?family=google:Lobster%20Two&format=font&chars=xy")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("2", response.headers["X-Glyphs"])
    assertTrue(response.bodyAsBytes().size < ttf.size / 20)
  }

  /**
   * Against Google itself, when `GOOGLE_FONTS_LIVE=1`: `emoji` is Noto Color Emoji, drawn in colour
   * (a 32-bit pack), one code point past the basic plane a glyph.
   */
  @Test
  fun liveEmoji() = testApplication {
    if (System.getenv("GOOGLE_FONTS_LIVE") != "1") return@testApplication
    val http = HttpClient(io.ktor.client.engine.cio.CIO)
    routing {
      glyphRangeRoutes(
        API_BASE,
        GlyphRanges(ServerText(CardFonts.fromClasspath()), GoogleFonts(http)),
      )
    }
    val response =
      client.get(
        "$API_BASE/fonts/glyphs?family=emoji&size=22&bits=32&chars=" +
          java.net.URLEncoder.encode("\uD83D\uDE80\uD83D\uDCF6", "UTF-8"),
      )
    assertEquals(HttpStatusCode.OK, response.status)
    val pack = assertNotNull(GlyphPackCodec.decode(response.bodyAsBytes()))
    assertEquals(listOf("\uD83D\uDCF6", "\uD83D\uDE80"), pack.glyphs.map { it.chars })
    val rocket = pack.glyphs.last()
    val px = assertNotNull(rocket.argb)
    // Colour: some pixel's channels differ (a grey mask's are equal).
    assertTrue(px.any { ((it shr 16) and 255) != (it and 255) })
    println(
      "emoji: ${pack.glyphs.map { "${it.chars} ${it.width}x${it.height} adv ${it.advance}" }}, ${response.bodyAsBytes().size} B",
    )
  }

  /**
   * Against Google itself, when `GOOGLE_FONTS_LIVE=1`: Lobster Two's glyphs, written as a sample to
   * `build/google-glyphs.png` to look at.
   */
  @Test
  fun liveLobsterTwo() = testApplication {
    if (System.getenv("GOOGLE_FONTS_LIVE") != "1") return@testApplication
    val http = HttpClient(io.ktor.client.engine.cio.CIO)
    routing {
      glyphRangeRoutes(
        API_BASE,
        GlyphRanges(ServerText(CardFonts.fromClasspath()), GoogleFonts(http)),
      )
    }
    val text = "Lobster Two 14sp"
    val response =
      client.get(
        "$API_BASE/fonts/glyphs?family=google:Lobster%20Two&size=28&density=2&chars=" +
          java.net.URLEncoder.encode(text, "UTF-8"),
      )
    assertEquals(HttpStatusCode.OK, response.status)
    val pack = assertNotNull(GlyphPackCodec.decode(response.bodyAsBytes()))
    println(
      "live: ${pack.glyphs.size} glyphs, line ${pack.lineHeight}, ${response.headers["ETag"]}",
    )
    val width = text.sumOf { c -> pack.glyphs.first { it.chars == c.toString() }.advance } + 8
    val image = BufferedImage(width, pack.lineHeight + 8, BufferedImage.TYPE_INT_RGB)
    var x = 4
    for (c in text) {
      val glyph = pack.glyphs.first { it.chars == c.toString() }
      x += glyph.marginLeft
      for (y in 0 until glyph.height) for (gx in 0 until glyph.width) {
        val a = glyph.coverage[y * glyph.width + gx].toInt() and 0xff
        if (a > 0) image.setRGB(x + gx, 4 + glyph.marginTop + y, (a shl 16) or (a shl 8) or a)
      }
      x += glyph.width + glyph.marginRight
    }
    ImageIO.write(image, "png", File("build/google-glyphs.png"))
  }
}
