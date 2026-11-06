package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.CardFontManifest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardFontsTest {
  private val face = byteArrayOf(0, 1, 0, 0, 7, 7)

  private val fonts = CardFonts { path ->
    when (path) {
      "fonts/v1/fonts.json" ->
        """
        {"families":[
          {"name":"digital7","role":"display","fonts":[{"file":"digital7.ttf","style":"italic"}]},
          {"name":"missing","fonts":[{"file":"gone.ttf"}]},
          {"name":"escape","fonts":[{"file":"../secret.ttf"}]}
        ]}
        """
          .trimIndent()
          .encodeToByteArray()
      "fonts/v1/digital7.ttf" -> face
      else -> null
    }
  }

  @Test
  fun theManifestCarriesEachFacesHashAndOnlyFacesItCanServe() {
    val manifest = CardFontManifest.decode(fonts.manifest.decodeToString())
    assertEquals(listOf("digital7"), manifest.families.map { it.name })
    val digital7 = manifest.families.single().fonts.single()
    assertEquals(Sha256.hex(face), digital7.sha256)
    assertTrue(digital7.italic)
  }

  @Test
  fun theFontsAreServedUnderMainAndEveryChannel() = testApplication {
    routing {
      cardFontRoutes(API_BASE, fonts)
      cardFontRoutes("$API_BASE/channels/{channel}", fonts)
    }
    for (base in listOf(API_BASE, "$API_BASE/channels/pr-1")) {
      val manifest = client.get("$base/fonts/fonts.json")
      assertEquals(HttpStatusCode.OK, manifest.status)
      assertEquals("*", manifest.headers[HttpHeaders.AccessControlAllowOrigin])
      assertTrue("digital7" in manifest.bodyAsText())

      val file = client.get("$base/fonts/digital7.ttf")
      assertContentEquals(face, file.bodyAsBytes())
    }
    val revalidated =
      client.get("$API_BASE/fonts/fonts.json") { header(HttpHeaders.IfNoneMatch, fonts.etag) }
    assertEquals(HttpStatusCode.NotModified, revalidated.status)
    assertEquals(HttpStatusCode.NotFound, client.get("$API_BASE/fonts/gone.ttf").status)
  }

  @Test
  fun theRepositorysFontsArePackaged() {
    val served = CardFontManifest.decode(CardFonts.fromClasspath().manifest.decodeToString())
    assertEquals(setOf("digital7", "gspot"), served.families.map { it.name }.toSet())
    assertTrue(served.faces.all { (_, face) -> face.sha256 != null })
  }
}
