package ee.schimke.ha.client

import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.CardSize
import ee.schimke.ha.model.ClientProfile
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `AddonClient` is the only piece of the chain that does I/O, so its failure-mode contract is what
 * the rest of the runtime relies on. The tests focus on the boundary: probe says yes/no, byte fetch
 * says yes/null. Everything above the client just composes those answers.
 */
class AddonClientTest {

  private val card =
    CardKey(dashboardUrlPath = "lovelace", viewPath = "home", cardIndex = 0, type = "tile")
  private val size = CardSize(widthPx = 320, heightPx = 160, densityDpi = 320)

  @Test
  fun health_returnsTrue_on200() = runTest {
    val engine = MockEngine { respondOk("ok") }
    val client = AddonClient("http://addon", engine = engine)
    assertTrue(client.health())
  }

  @Test
  fun health_returnsFalse_on503() = runTest {
    val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
    val client = AddonClient("http://addon", engine = engine)
    assertFalse(client.health())
  }

  @Test
  fun health_returnsFalse_onIOException() = runTest {
    val engine = MockEngine { throw RuntimeException("network down") }
    val client = AddonClient("http://addon", engine = engine)
    assertFalse(client.health())
  }

  @Test
  fun fetchCardBytes_returnsBytes_on200() = runTest {
    val payload = ByteArray(8) { it.toByte() }
    val engine = MockEngine { request ->
      // Verify the URL the client builds — width / height / density
      // / profile in query string, cache key in path.
      val url = request.url.toString()
      assertTrue(url.contains("/v1/cards/"))
      assertTrue(url.contains("w=320"))
      assertTrue(url.contains("h=160"))
      assertTrue(url.contains("density=320"))
      assertTrue(url.contains("profile=phone"))
      respond(
        content = ByteReadChannel(payload),
        status = HttpStatusCode.OK,
        headers = headersOf("Content-Type", "application/x-remote-compose"),
      )
    }
    val client = AddonClient("http://addon", engine = engine)
    val bytes = client.fetchCardBytes(card, size, ClientProfile.Phone)
    assertNotNull(bytes)
    assertEquals(8, bytes.bytes.size)
    assertEquals(320, bytes.widthPx)
    assertEquals(160, bytes.heightPx)
  }

  @Test
  fun fetchCardBytes_returnsNull_on501() = runTest {
    val engine = MockEngine { respondError(HttpStatusCode.NotImplemented) }
    val client = AddonClient("http://addon", engine = engine)
    assertNull(client.fetchCardBytes(card, size, ClientProfile.Phone))
  }

  @Test
  fun fetchCardBytes_returnsNull_on404() = runTest {
    val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
    val client = AddonClient("http://addon", engine = engine)
    assertNull(client.fetchCardBytes(card, size, ClientProfile.Phone))
  }

  @Test
  fun fetchCardBytes_returnsNull_onNetworkError() = runTest {
    val engine = MockEngine { throw RuntimeException("dns") }
    val client = AddonClient("http://addon", engine = engine)
    assertNull(client.fetchCardBytes(card, size, ClientProfile.Phone))
  }

  @Test
  fun fetchCardBytes_sendsBearerToken_whenConfigured() = runTest {
    var sawAuth: String? = null
    val engine = MockEngine { request ->
      sawAuth = request.headers["Authorization"]
      respond(content = ByteReadChannel(ByteArray(0)), status = HttpStatusCode.OK)
    }
    val client = AddonClient("http://addon", accessToken = "secret", engine = engine)
    client.fetchCardBytes(card, size, ClientProfile.Phone)
    assertEquals("Bearer secret", sawAuth)
  }
}
