package ee.schimke.ha.client.design

import ee.schimke.ha.design.CardFontFace
import ee.schimke.ha.design.CardFontFamily
import ee.schimke.ha.design.CardFontManifest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DesignFontsTest {
  private val digital7 = byteArrayOf(0, 1, 0, 0, 1)
  private val russo = byteArrayOf(0, 1, 0, 0, 2)

  private fun manifest(russoSha: String = Sha256.hex(russo)) =
    CardFontManifest(
        listOf(
          CardFontFamily(
            "digital7",
            "display",
            listOf(CardFontFace("digital7.ttf", style = "italic", sha256 = Sha256.hex(digital7))),
          ),
          CardFontFamily(
            "gspot",
            "heading",
            listOf(CardFontFace("russo-one.ttf", sha256 = russoSha)),
          ),
        ),
      )
      .encode()

  private val versions = mutableListOf<String?>()

  private class MemoryFontStore : FontStore {
    var manifest: String? = null
    val faces = mutableMapOf<String, ByteArray>()

    override suspend fun readManifest() = manifest

    override suspend fun writeManifest(manifest: String) {
      this.manifest = manifest
    }

    override suspend fun readFace(sha256: String) = faces[sha256]

    override suspend fun writeFace(sha256: String, bytes: ByteArray) {
      faces[sha256] = bytes
    }
  }

  private fun client(
    manifest: String,
    requests: MutableList<String> = mutableListOf(),
    bearers: MutableList<String?> = mutableListOf(),
  ) =
    HttpClient(
      MockEngine { request ->
        val path = request.url.encodedPath
        requests += path
        if (path.endsWith(".ttf")) versions += request.url.parameters["v"]
        bearers += request.headers[HttpHeaders.Authorization]
        when (path) {
          "/designs/v1/fonts/fonts.json" -> respond(manifest, HttpStatusCode.OK)
          "/designs/v1/fonts/digital7.ttf" -> respond(digital7, HttpStatusCode.OK)
          "/designs/v1/fonts/russo-one.ttf" -> respond(russo, HttpStatusCode.OK)
          else -> respond("", HttpStatusCode.NotFound)
        }
      },
    )

  @Test
  fun aRefreshStoresEveryFaceAndTheCacheServesThemByFamily() = runTest {
    val store = MemoryFontStore()
    assertTrue(DesignFonts.refresh("https://ha.coo.ee/designs/v1/", client(manifest()), store))
    val cached = DesignFonts.cached(store).associateBy { it.family }
    assertContentEquals(digital7, cached.getValue("digital7").bytes)
    assertTrue(cached.getValue("digital7").italic)
    assertContentEquals(russo, cached.getValue("gspot").bytes)
    assertEquals(setOf(Sha256.hex(digital7), Sha256.hex(russo)), versions.toSet(), "faces by hash")
  }

  @Test
  fun aStoredFaceIsNeverFetchedAgain() = runTest {
    val store = MemoryFontStore()
    DesignFonts.refresh("https://ha.coo.ee/designs/v1", client(manifest()), store)
    val requests = mutableListOf<String>()
    assertFalse(
      DesignFonts.refresh("https://ha.coo.ee/designs/v1", client(manifest(), requests), store),
    )
    assertEquals(listOf("/designs/v1/fonts/fonts.json"), requests)
  }

  @Test
  fun aFaceThatDoesNotHashToItsManifestIsNotStored() = runTest {
    val store = MemoryFontStore()
    DesignFonts.refresh(
      "https://ha.coo.ee/designs/v1",
      client(manifest(russoSha = "0".repeat(64))),
      store,
    )
    assertEquals(listOf("digital7"), DesignFonts.cached(store).map { it.family })
  }

  @Test
  fun aReplacementThatFailsKeepsTheFaceItReplaces() = runTest {
    val store = MemoryFontStore()
    DesignFonts.refresh("https://ha.coo.ee/designs/v1", client(manifest()), store)
    // The server now names a new Russo One whose bytes don't arrive as hashed.
    DesignFonts.refresh(
      "https://ha.coo.ee/designs/v1",
      client(manifest(russoSha = "1".repeat(64))),
      store,
    )
    val cached = DesignFonts.cached(store).associateBy { it.family }
    assertContentEquals(russo, cached.getValue("gspot").bytes)
    assertEquals(2, cached.size)
  }

  @Test
  fun aFailedFetchKeepsWhatWasStored() = runTest {
    val store = MemoryFontStore()
    DesignFonts.refresh("https://ha.coo.ee/designs/v1", client(manifest()), store)
    val down = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
    assertFalse(DesignFonts.refresh("https://ha.coo.ee/designs/v1", down, store))
    assertEquals(2, DesignFonts.cached(store).size)
  }

  @Test
  fun everyRequestCarriesTheServersBearer() = runTest {
    val bearers = mutableListOf<String?>()
    DesignFonts.refresh(
      "https://ha.coo.ee/designs/v1",
      client(manifest(), bearers = bearers),
      MemoryFontStore(),
      token = { "ha-access" },
    )
    assertEquals(listOf("Bearer ha-access"), bearers.distinct())
    assertEquals(3, bearers.size, "the manifest and both faces")
  }
}
