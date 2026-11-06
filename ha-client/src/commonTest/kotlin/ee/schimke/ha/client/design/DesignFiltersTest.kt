package ee.schimke.ha.client.design

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DesignFiltersTest {
  private val document = "..digital7..".encodeToByteArray()

  @Test
  fun theDocumentGoesToTheFilterForItsHostWithTheServersBearer() = runTest {
    val seen = mutableListOf<String>()
    val client =
      HttpClient(
        MockEngine { request ->
          seen += request.url.encodedPath
          seen += request.url.parameters["host"].orEmpty()
          seen += request.url.parameters["density"].orEmpty()
          seen += request.headers[HttpHeaders.Authorization].orEmpty()
          seen += request.url.parameters["locale"].orEmpty()
          seen += request.url.parameters["values"].orEmpty()
          assertContentEquals(document, request.body.toByteArray())
          respond(byteArrayOf(7, 7), HttpStatusCode.OK)
        },
      )
    val filtered =
      DesignFilters.bitmapFonts(
        "https://ha.coo.ee/designs/v1/",
        document,
        DesignFilters.WEAR_WIDGETS,
        2f,
        client,
        token = { "device" },
        locale = "de-DE",
        values = mapOf("sensor.s0.state" to listOf("on", "off")),
      )
    assertContentEquals(byteArrayOf(7, 7), filtered)
    assertEquals(
      listOf(
        "/designs/v1/filters/bitmap-fonts",
        "wear-widgets",
        "2.0",
        "Bearer device",
        "de-DE",
        """{"sensor.s0.state":["on","off"]}""",
      ),
      seen,
    )
  }

  @Test
  fun aServerThatCantFilterGivesNothingSoTheDocumentGoesAsItIs() = runTest {
    val older = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
    assertNull(
      DesignFilters.bitmapFonts("https://old", document, DesignFilters.LAUNCHER_WIDGETS, 2f, older),
    )
  }

  @Test
  fun onlyADocumentNamingACardFontIsWorthARequest() {
    assertTrue(DesignFilters.namesAny(document, setOf("gspot", "digital7")))
    assertFalse(DesignFilters.namesAny("sans-serif".encodeToByteArray(), setOf("digital7")))
  }
}
