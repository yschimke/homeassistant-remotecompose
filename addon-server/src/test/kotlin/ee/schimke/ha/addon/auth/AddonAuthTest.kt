package ee.schimke.ha.addon.auth

import ee.schimke.ha.addon.bridge.HaSupervisorBridge
import ee.schimke.ha.addon.configure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class AddonAuthTest {
  // Never started: the routes under test only read its (empty) state cache.
  private val bridge = HaSupervisorBridge("http://ha.invalid", "supervisor-token")

  @AfterTest fun closeBridge() = runBlocking { bridge.close() }

  private val validTokens = setOf("good-token")
  private val validator = TokenValidator {
    if (it in validTokens) TokenCheck.Valid else TokenCheck.Invalid
  }

  /**
   * Boots the real add-on module. testApplication's in-memory engine reports a fixed TCP peer
   * address; [peerIsIngress] decides whether that peer is configured as the supervisor ingress
   * proxy, which is how ingress vs direct-port traffic differ on the wire.
   */
  private fun authApp(peerIsIngress: Boolean, block: suspend ApplicationTestBuilder.() -> Unit) =
    testApplication {
      application {
        configure(bridge) {
          trustedProxyAddresses =
            if (peerIsIngress) setOf(TEST_PEER) else setOf(SUPERVISOR_INGRESS_ADDRESS)
          tokenValidator = validator
        }
      }
      block()
    }

  @Test
  fun ingressProxyNeedsNoToken() =
    authApp(peerIsIngress = true) {
      val response =
        client.get("/v1/snapshot") { header("X-Ingress-Path", "/api/hassio_ingress/abc") }
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains("\"states\""))
    }

  @Test
  fun directRequestWithoutTokenIsRejected() =
    authApp(peerIsIngress = false) {
      val response = client.get("/v1/snapshot")
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertTrue(response.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer"))
      // The route never ran: no snapshot leaked alongside the 401.
      assertEquals("""{"error":"missing bearer token"}""", response.bodyAsText())
    }

  @Test
  fun ingressHeaderAloneDoesNotAuthenticate() =
    authApp(peerIsIngress = false) {
      // X-Ingress-Path / X-Forwarded-For are client-controlled on the direct port.
      val response =
        client.get("/v1/snapshot") {
          header("X-Ingress-Path", "/api/hassio_ingress/abc")
          header("X-Forwarded-For", SUPERVISOR_INGRESS_ADDRESS)
        }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun directRequestWithValidTokenIsAccepted() =
    authApp(peerIsIngress = false) {
      val response = client.get("/v1/snapshot") { bearerAuth("good-token") }
      assertEquals(HttpStatusCode.OK, response.status)
    }

  @Test
  fun directRequestWithInvalidTokenIsRejected() =
    authApp(peerIsIngress = false) {
      val response = client.get("/v1/snapshot") { bearerAuth("stolen-or-made-up") }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      // Not a bearer scheme at all.
      assertEquals(
        HttpStatusCode.Unauthorized,
        client
          .get("/v1/snapshot") { header(HttpHeaders.Authorization, "Basic Z29vZC10b2tlbg==") }
          .status,
      )
    }

  @Test
  fun healthProbesStayPublic() =
    authApp(peerIsIngress = false) {
      assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
      assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/readyz").status)
    }

  @Test
  fun unavailableValidatorFailsClosed() = testApplication {
    application {
      configure(bridge) {
        trustedProxyAddresses = emptySet()
        tokenValidator = TokenValidator { TokenCheck.Unavailable }
      }
    }
    val response = client.get("/v1/snapshot") { bearerAuth("good-token") }
    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
  }

  @Test
  fun streamWithoutTokenIsRefusedBeforeUpgrade() =
    authApp(peerIsIngress = false) {
      val ws = createClient { install(WebSockets) }
      assertFailsWith<Exception> { ws.webSocket("/v1/stream") { incoming.receive() } }
    }

  @Test
  fun streamWithValidTokenConnects() =
    authApp(peerIsIngress = false) {
      val ws = createClient { install(WebSockets) }
      ws.webSocket("/v1/stream", request = { bearerAuth("good-token") }) {
        val first = incoming.receive() as Frame.Text
        assertEquals("""{"type":"ready"}""", first.readText())
      }
    }

  @Test
  fun streamThroughIngressConnects() =
    authApp(peerIsIngress = true) {
      val ws = createClient { install(WebSockets) }
      ws.webSocket("/v1/stream") {
        val first = incoming.receive() as Frame.Text
        assertEquals("""{"type":"ready"}""", first.readText())
      }
    }

  @Test
  fun parsesBearerHeaders() {
    assertEquals("abc", bearerToken("Bearer abc"))
    assertEquals("abc", bearerToken("bearer   abc"))
    assertEquals(null, bearerToken("Bearer "))
    assertEquals(null, bearerToken("Basic abc"))
    assertEquals(null, bearerToken(null))
    assertEquals(SUPERVISOR_INGRESS_ADDRESS, normalizeAddress("::ffff:172.30.32.2"))
  }

  @Test
  fun haTokenValidatorAsksCoreWithTheCallersToken() = runTest {
    val seen = mutableListOf<String?>()
    val http =
      HttpClient(
        MockEngine { request ->
          seen += request.headers[HttpHeaders.Authorization]
          assertEquals("http://homeassistant:8123/api/", request.url.toString())
          when (request.headers[HttpHeaders.Authorization]) {
            "Bearer good-token" ->
              respond("""{"message":"API running."}""", HttpStatusCode.OK, JSON)
            "Bearer boom" -> respond("", HttpStatusCode.BadGateway)
            else -> respond("""{"message":"401: Unauthorized"}""", HttpStatusCode.Unauthorized)
          }
        }
      )
    val validator = HaTokenValidator(http) { "http://homeassistant:8123" }
    assertEquals(TokenCheck.Valid, validator.validate("good-token"))
    assertEquals(TokenCheck.Invalid, validator.validate("bad-token"))
    assertEquals(TokenCheck.Unavailable, validator.validate("boom"))
    assertEquals<List<String?>>(
      listOf("Bearer good-token", "Bearer bad-token", "Bearer boom"),
      seen,
    )
    assertEquals(TokenCheck.Unavailable, HaTokenValidator(http) { null }.validate("good-token"))
  }

  @Test
  fun cachesOnlyPositiveResultsForTheTtl() = runTest {
    val time = TestTimeSource()
    var calls = 0
    val cached =
      CachingTokenValidator(
        TokenValidator {
          calls++
          if (it == "good-token") TokenCheck.Valid else TokenCheck.Invalid
        },
        ttl = 60.seconds,
        timeSource = time,
      )
    assertEquals(TokenCheck.Valid, cached.validate("good-token"))
    assertEquals(TokenCheck.Valid, cached.validate("good-token"))
    assertEquals(1, calls)
    assertEquals(TokenCheck.Invalid, cached.validate("bad-token"))
    assertEquals(TokenCheck.Invalid, cached.validate("bad-token"))
    assertEquals(3, calls)
    time += 61.seconds
    assertEquals(TokenCheck.Valid, cached.validate("good-token"))
    assertEquals(4, calls)
  }

  @Test
  fun discoversCoreUrlFromSupervisor() = runTest {
    fun supervisor(body: String) =
      HttpClient(
        MockEngine { request ->
          assertEquals("http://supervisor/core/info", request.url.toString())
          assertEquals("Bearer supervisor-token", request.headers[HttpHeaders.Authorization])
          respond(body, HttpStatusCode.OK, JSON)
        }
      )
    val plain = supervisor("""{"result":"ok","data":{"port":8124,"ssl":false}}""")
    assertEquals("http://homeassistant:8124", SupervisorCoreUrl(plain, "supervisor-token")())
    val tls = supervisor("""{"result":"ok","data":{"port":8123,"ssl":true}}""")
    assertEquals(null, SupervisorCoreUrl(tls, "supervisor-token")())
  }

  private companion object {
    /** The peer address testApplication's in-memory engine reports for every call. */
    const val TEST_PEER = "localhost"
    val JSON = headersOf(HttpHeaders.ContentType, "application/json")
  }
}
