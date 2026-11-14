package ee.schimke.ha.client.design.glyphs

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GlyphCacheTest {
  /**
   * A 3x2 glyph for [chars] with coverage 0, 85, 170, 255, 255, 0, standing [above] the baseline.
   */
  private fun glyph(chars: String, top: Int = 0, lineHeight: Int = 4) =
    GlyphPack.Glyph(
      chars,
      marginLeft = 0,
      marginTop = top,
      marginRight = 1,
      marginBottom = lineHeight - top - 2,
      width = 3,
      height = 2,
      coverage = byteArrayOf(0, 85, 170.toByte(), 255.toByte(), 255.toByte(), 0),
    )

  private fun pack(vararg chars: String, baseline: Int = 3, lineHeight: Int = 4) =
    GlyphPack(
      "roboto",
      14f,
      2f,
      400,
      false,
      lineHeight,
      baseline,
      chars.map { glyph(it, top = baseline - 2, lineHeight = lineHeight) },
      kerning = mapOf("AV" to -1),
    )

  @Test
  fun aPackRoundTripsAtEveryDepth() {
    val pack = pack("A", "V")
    for (bits in GlyphPackCodec.BITS) {
      val decoded = assertNotNull(GlyphPackCodec.decode(GlyphPackCodec.encode(pack, bits)), "$bits")
      assertEquals(pack.key, decoded.key)
      assertEquals(listOf("A", "V"), decoded.glyphs.map { it.chars })
      assertEquals(mapOf("AV" to -1), decoded.kerning)
      assertEquals(4, decoded.glyphs[0].advance)
      if (bits == 8) assertContentEquals(pack.glyphs[0].coverage, decoded.glyphs[0].coverage)
    }
  }

  @Test
  fun aColourPackKeepsEachPixelAndItsCoverage() {
    val rocket = 0x80402010.toInt() // premultiplied: alpha 0x80
    val glyph =
      GlyphPack.Glyph(
        "\uD83D\uDE80",
        marginLeft = 1,
        marginTop = 0,
        marginRight = 1,
        marginBottom = 2,
        width = 2,
        height = 2,
        coverage = byteArrayOf(0x80.toByte(), 0, 0, 0xff.toByte()),
        argb = intArrayOf(rocket, 0, 0, 0xff00ff00.toInt()),
      )
    val pack = GlyphPack("emoji", 22f, 1f, 400, false, 4, 3, listOf(glyph, glyph("A")))
    val bytes = GlyphPackCodec.encode(pack, GlyphPackCodec.COLOR_BITS)
    val decoded = assertNotNull(GlyphPackCodec.decode(bytes))
    val emoji = decoded.glyphs.first { it.chars == "\uD83D\uDE80" }
    assertContentEquals(intArrayOf(rocket, 0, 0, 0xff00ff00.toInt()), emoji.argb)
    assertContentEquals(byteArrayOf(0x80.toByte(), 0, 0, 0xff.toByte()), emoji.coverage)
    // A coverage glyph in a colour pack travels as white at its coverage.
    val a = decoded.glyphs.first { it.chars == "A" }
    assertEquals(0x55555555, a.argb!![1])
  }

  @Test
  fun coverageIsQuantisedToItsDepth() {
    val two = GlyphPackCodec.decode(GlyphPackCodec.encode(pack("A"), bits = 2))!!.glyphs[0]
    assertContentEquals(
      byteArrayOf(0, 85, 170.toByte(), 255.toByte(), 255.toByte(), 0),
      two.coverage,
    )
    val one = GlyphPackCodec.decode(GlyphPackCodec.encode(pack("A"), bits = 1))!!.glyphs[0]
    assertContentEquals(
      byteArrayOf(0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 0),
      one.coverage,
    )
    assertTrue(
      GlyphPackCodec.encode(pack("A"), 1).size < GlyphPackCodec.encode(pack("A"), 8).size,
      "a bit a pixel travels smaller",
    )
  }

  @Test
  fun bytesThatArentAPackDecodeToNull() {
    assertNull(GlyphPackCodec.decode(byteArrayOf(1, 2, 3)))
    val bytes = GlyphPackCodec.encode(pack("A"))
    assertNull(GlyphPackCodec.decode(bytes.copyOf(bytes.size - 1)), "truncated")
  }

  @Test
  fun mergingSetsBothPacksGlyphsOnTheLowerBaseline() {
    // The second request's tallest glyph stood higher: its baseline is two pixels lower.
    val first = pack("a", baseline = 3, lineHeight = 4)
    val second = pack("b", baseline = 5, lineHeight = 6)
    val merged = first.merge(second)
    assertEquals(5, merged.baseline)
    assertEquals(6, merged.lineHeight)
    for (glyph in merged.glyphs) {
      assertEquals(merged.baseline - 2, glyph.marginTop, "${glyph.chars} stands on the baseline")
      assertEquals(merged.lineHeight, glyph.marginTop + glyph.height + glyph.marginBottom)
    }
  }

  private fun server(requests: MutableList<String>, offline: () -> Boolean = { false }) =
    HttpClient(
      MockEngine { request ->
        requests += request.url.toString()
        if (offline()) return@MockEngine respond("", HttpStatusCode.ServiceUnavailable)
        val chars = GlyphRequest.charsOf(request.url.parameters["chars"].orEmpty())
        // A face with no glyph for "?".
        val drawn = (chars - "?").sorted().toTypedArray()
        respond(
          GlyphPackCodec.encode(pack(*drawn), bits = request.url.parameters["bits"]!!.toInt()),
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, GlyphPack.CONTENT_TYPE),
        )
      },
    )

  private fun request(text: String) =
    GlyphRequest("roboto", 14f, 2f, chars = GlyphRequest.charsOf(text))

  @Test
  fun aCacheFetchesOnlyTheCharactersItHasntStored() = runTest {
    val requests = mutableListOf<String>()
    val store = InMemoryGlyphStore()
    val cache = GlyphCache("https://example.test/designs/v1/", store, server(requests), bits = 2)

    val first = assertNotNull(cache.glyphs(request("12")))
    assertEquals(setOf("1", "2"), first.chars)
    assertTrue("bits=2" in requests.single() && "chars=12" in requests.single(), requests.single())

    val second = assertNotNull(cache.glyphs(request("21:3")))
    assertEquals(setOf("1", "2", "3", ":"), second.chars, "the stored glyphs and the fetched")
    assertTrue("chars=%3A3" in requests[1] || "chars=3%3A" in requests[1], requests[1])

    cache.glyphs(request("123"))
    assertEquals(2, requests.size, "nothing missing: nothing fetched")
    assertNotNull(store.read(request("1").key), "kept in the store")
  }

  @Test
  fun aCharacterTheFaceCantDrawIsAskedForOnce() = runTest {
    val requests = mutableListOf<String>()
    val cache = GlyphCache("https://example.test", InMemoryGlyphStore(), server(requests))
    assertEquals(setOf("a"), cache.glyphs(request("a?"))!!.chars)
    cache.glyphs(request("a?"))
    assertEquals(1, requests.size)
  }

  @Test
  fun offlineACacheHasWhatItStored() = runTest {
    var offline = false
    val requests = mutableListOf<String>()
    val store = InMemoryGlyphStore()
    GlyphCache("https://example.test", store, server(requests) { offline }).glyphs(request("ab"))
    offline = true
    val cache = GlyphCache("https://example.test", store, server(requests) { offline })
    assertEquals(setOf("a", "b"), cache.glyphs(request("abc"))!!.chars)
  }
}
