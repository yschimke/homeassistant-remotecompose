package ee.schimke.terrazzo.desktop

import ee.schimke.terrazzo.shared.CardAgentSessions
import ee.schimke.terrazzo.shared.SavedCardAgentSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DesktopHomeAssistantAuthTest {
  private class MemoryStore : DesktopCredentialStore {
    var value: DesktopSavedSignIn? = null
    var failWrite = false
    var failClear = false

    override fun read() = value

    override fun write(signIn: DesktopSavedSignIn) {
      check(!failWrite)
      value = signIn
    }

    override fun clear() {
      check(!failClear)
      value = null
    }
  }

  private fun token(access: String = "access", refresh: String? = "refresh") =
    """{"access_token":"$access","expires_in":60${refresh?.let { ",\"refresh_token\":\"$it\"" }.orEmpty()}}"""

  private fun callback(uri: String, method: String = "GET"): Int =
    java.net.http.HttpClient.newHttpClient()
      .send(
        HttpRequest.newBuilder(URI(uri))
          .method(method, HttpRequest.BodyPublishers.noBody())
          .build(),
        HttpResponse.BodyHandlers.discarding(),
      )
      .statusCode()

  private fun authorize(uri: URI) {
    val params = Url(uri.toString()).parameters
    assertEquals("S256", params["code_challenge_method"])
    assertEquals(DESKTOP_CLIENT_ID, params["client_id"])
    assertEquals(200, callback("${params["redirect_uri"]}?state=${params["state"]}&code=approved"))
  }

  @Test
  fun browserExchangeRefreshRestoreAndSignOut() =
    runBlocking<Unit> {
      val store = MemoryStore()
      var clock = 0L
      val forms = mutableListOf<Parameters>()
      var challenge: String? = null
      val http =
        HttpClient(
          MockEngine { request ->
            assertEquals("https://ha.test/auth/token", request.url.toString())
            val form =
              parseQueryString(
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
              )
            forms.add(form)
            when (form["grant_type"]) {
              "authorization_code" -> {
                assertEquals("approved", form["code"])
                assertEquals(challenge, pkceChallenge(checkNotNull(form["code_verifier"])))
                respond(token())
              }
              "refresh_token" -> {
                assertEquals("refresh", form["refresh_token"])
                respond(token("renewed", null))
              }
              else -> {
                assertEquals("revoke", form["action"])
                assertEquals("refresh", form["token"])
                respond("")
              }
            }
          },
        )
      val auth =
        DesktopHomeAssistantAuth(
          store,
          http,
          { uri ->
            challenge = Url(uri.toString()).parameters["code_challenge"]
            authorize(uri)
          },
          { DesktopOAuthCallback(0) },
          { clock },
        )
      try {
        auth.signIn("https://ha.test")
        assertEquals("https://ha.test", auth.session.value?.server)
        assertEquals("refresh", store.value?.refreshToken)
        assertEquals("access", auth.currentAccessToken())
        assertEquals(1, forms.size)
        clock = 31_000
        assertEquals(
          listOf("renewed", "renewed"),
          coroutineScope {
            listOf(async { auth.currentAccessToken() }, async { auth.currentAccessToken() })
              .awaitAll()
          },
        )
        assertEquals(2, forms.size, "Concurrent consumers share a refresh")
        // A new process restores the refresh token without opening the browser.
        val restored =
          DesktopHomeAssistantAuth(store, http, { error("Must not open browser") }, now = { clock })
        restored.restore()
        assertEquals("renewed", restored.currentAccessToken())
        assertEquals("https://ha.test", restored.session.value?.server)
        restored.signOut()
        assertNull(restored.session.value)
        assertNull(store.value)
        assertFailsWith<DesktopSignInExpired> { restored.currentAccessToken() }
        assertEquals(4, forms.size)
        assertTrue(
          forms.filter { it["grant_type"] != null }.all { it["client_id"] == DESKTOP_CLIENT_ID },
        )
      } finally {
        auth.close()
      }
    }

  @Test
  fun forgedMalformedAndUnrelatedCallbacksDoNotConsumeSignIn() =
    runBlocking<Unit> {
      DesktopOAuthCallback(0).use { pending ->
        assertEquals(400, callback("${pending.redirectUri}?state=wrong&code=attacker"))
        assertEquals(
          400,
          callback("${pending.redirectUri}?state=${pending.state}&state=${pending.state}&code=x"),
        )
        assertEquals(400, callback("${pending.redirectUri}?state=${pending.state}&code=a&code=b"))
        assertEquals(400, callback("${pending.origin}favicon.ico"))
        assertEquals(400, callback("${pending.redirectUri}?state=${pending.state}&code=a", "POST"))
        assertEquals(200, callback("${pending.redirectUri}?state=${pending.state}&code=approved"))
        assertEquals("approved", pending.awaitCode())
        assertEquals(400, callback("${pending.redirectUri}?state=${pending.state}&code=replayed"))
      }
    }

  @Test
  fun denialDoesNotExchangeCode() =
    runBlocking<Unit> {
      val http = HttpClient(MockEngine { error("No token request after denial") })
      val auth =
        DesktopHomeAssistantAuth(
          MemoryStore(),
          http,
          { uri ->
            val params = Url(uri.toString()).parameters
            callback("${params["redirect_uri"]}?state=${params["state"]}&error=access_denied")
          },
          { DesktopOAuthCallback(0) },
        )
      try {
        assertFailsWith<IllegalStateException> { auth.signIn("https://ha.test") }
        assertNull(auth.session.value)
      } finally {
        auth.close()
      }
    }

  @Test
  fun timeoutAndCancellationReleaseLoopbackListener() =
    runBlocking<Unit> {
      val pending = CompletableDeferred<DesktopOAuthCallback>()
      val http = HttpClient(MockEngine { error("No token request") })
      val auth =
        DesktopHomeAssistantAuth(
          MemoryStore(),
          http,
          {},
          { DesktopOAuthCallback(0).also { pending.complete(it) } },
        )
      try {
        val job = launch { auth.signIn("https://ha.test") }
        val port = Url(pending.await().origin).port
        job.cancelAndJoin()
        DesktopOAuthCallback(port).close()
        val timed =
          DesktopHomeAssistantAuth(
            MemoryStore(),
            http,
            {},
            { DesktopOAuthCallback(0) },
            signInTimeoutMillis = 25,
          )
        assertFailsWith<TimeoutCancellationException> { timed.signIn("https://ha.test") }
        assertNull(auth.session.value)
      } finally {
        auth.close()
      }
    }

  @Test
  fun rejectedRefreshClearsSavedSessionButNetworkFailureKeepsIt() =
    runBlocking<Unit> {
      val store = MemoryStore().apply { value = DesktopSavedSignIn("https://ha.test", "refresh") }
      var status = HttpStatusCode.ServiceUnavailable
      val http = HttpClient(MockEngine { respond("""{"error":"invalid_grant"}""", status) })
      val auth = DesktopHomeAssistantAuth(store, http)
      try {
        assertFailsWith<IllegalStateException> { auth.restore() }
        assertNotNull(store.value)
        status = HttpStatusCode.BadRequest
        auth.restore()
        assertNull(store.value)
        assertNull(auth.session.value)
        assertFailsWith<DesktopSignInExpired> { auth.currentAccessToken() }
      } finally {
        auth.close()
      }
    }

  @Test
  fun credentialWriteFailureDoesNotPublishSessionAndDeletionCanBeRetried() =
    runBlocking<Unit> {
      val store = MemoryStore().apply { failWrite = true }
      val http = HttpClient(MockEngine { respond(token()) })
      val auth = DesktopHomeAssistantAuth(store, http, ::authorize, { DesktopOAuthCallback(0) })
      try {
        assertFailsWith<DesktopCredentialStoreUnavailable> { auth.signIn("https://ha.test") }
        assertNull(auth.session.value)
        store.failWrite = false
        auth.signIn("https://ha.test")
        store.failClear = true
        assertFailsWith<IllegalStateException> { auth.signOut() }
        assertNotNull(auth.session.value)
        store.failClear = false
        auth.signOut()
        assertNull(store.value)
        assertNull(auth.session.value)
      } finally {
        auth.close()
      }
    }

  @Test
  fun activeSessionPersistsRotationAndReturnsToSignInOnlyOnRejectedRefresh() =
    runBlocking<Unit> {
      val store = MemoryStore().apply { value = DesktopSavedSignIn("https://ha.test", "refresh") }
      var clock = 0L
      var status = HttpStatusCode.OK
      var body = token()
      val http = HttpClient(MockEngine { respond(body, status) })
      val auth = DesktopHomeAssistantAuth(store, http, now = { clock })
      try {
        auth.restore()
        clock = 31_000
        status = HttpStatusCode.ServiceUnavailable
        assertFailsWith<IllegalStateException> { auth.currentAccessToken() }
        assertNotNull(auth.session.value)
        assertEquals("refresh", store.value?.refreshToken)
        status = HttpStatusCode.OK
        body = token("rotated-access", "rotated-refresh")
        assertEquals("rotated-access", auth.currentAccessToken())
        assertEquals("rotated-refresh", store.value?.refreshToken)
        clock = 62_000
        status = HttpStatusCode.BadRequest
        body = """{"error":"invalid_grant"}"""
        assertFailsWith<DesktopSignInExpired> { auth.currentAccessToken() }
        assertNull(auth.session.value)
        assertNull(store.value)
      } finally {
        auth.close()
      }
    }

  @Test
  fun rejectedRefreshForgetsCardCapabilitiesBeforeReturningToSignIn() =
    runBlocking<Unit> {
      val keys = listOf("https://ha.test:card-a", "https://ha.test:card-b")
      val capabilities =
        SavedCardAgentSession(
          "https://design.test",
          "old-owner",
          "old request",
          Long.MAX_VALUE,
          "old-agent",
        )
      for (restoring in listOf(false, true)) {
        for (failClear in listOf(false, true)) {
          val store =
            MemoryStore().apply { value = DesktopSavedSignIn("https://ha.test", "refresh") }
          var rejected = false
          val http =
            HttpClient(
              MockEngine {
                if (rejected) respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest)
                else respond(token())
              },
            )
          val auth = DesktopHomeAssistantAuth(store, http, now = { if (rejected) 31_000L else 0L })
          var publishedCapabilities: Deferred<SavedCardAgentSession?>? = null
          try {
            if (!restoring) auth.restore()
            keys.forEach { CardAgentSessions.put(it, capabilities) }
            if (!restoring) {
              publishedCapabilities =
                async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                  auth.session.first { it == null }
                  CardAgentSessions.get(keys.first())
                }
            }
            rejected = true
            store.failClear = failClear
            if (failClear) {
              assertFailsWith<IllegalStateException> {
                if (restoring) auth.restore() else auth.currentAccessToken()
              }
            } else if (restoring) auth.restore()
            else assertFailsWith<DesktopSignInExpired> { auth.currentAccessToken() }
            assertNull(auth.session.value)
            keys.forEach {
              assertNull(CardAgentSessions.get(it), "A new user must not recover $it")
            }
            publishedCapabilities?.let { assertNull(withTimeout(1_000) { it.await() }) }
            assertFailsWith<DesktopSignInExpired> { auth.currentAccessToken() }
          } finally {
            publishedCapabilities?.cancel()
            CardAgentSessions.clear()
            auth.close()
          }
        }
      }
    }

  @Test
  fun gatewayErrorsPreserveCredentialsAndAllowRecoveryDuringRestoreAndRefresh() =
    runBlocking<Unit> {
      for (active in listOf(false, true)) {
        for (status in
          listOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
          )) {
          for (body in
            listOf(
              "",
              "<html>Gateway denied access</html>",
              "{invalid json",
              "{}",
              """{"error":"temporarily_unavailable"}""",
              """{"error":{"code":"invalid_grant"}}""",
            )) {
            val saved = DesktopSavedSignIn("https://ha.test", "refresh")
            val store = MemoryStore().apply { value = saved }
            var clock = 0L
            var gatewayFailure = false
            val http =
              HttpClient(
                MockEngine {
                  if (gatewayFailure) respond(body, status) else respond(token("recovered", null))
                },
              )
            val auth = DesktopHomeAssistantAuth(store, http, now = { clock })
            val key = "https://ha.test:card-a"
            val capabilities =
              SavedCardAgentSession(
                "https://design.test",
                "owner",
                "request",
                Long.MAX_VALUE,
                "agent",
              )
            try {
              if (active) auth.restore()
              CardAgentSessions.put(key, capabilities)
              val session = auth.session.value
              clock = 31_000
              gatewayFailure = true
              val failure =
                assertFailsWith<IllegalStateException> {
                  if (active) auth.currentAccessToken() else auth.restore()
                }
              assertFalse(failure is DesktopSignInExpired)
              assertSame(saved, store.value)
              assertSame(session, auth.session.value)
              assertEquals(capabilities, CardAgentSessions.get(key))
              gatewayFailure = false
              if (!active) auth.restore()
              assertEquals("recovered", auth.currentAccessToken())
              assertNotNull(auth.session.value)
              assertEquals(capabilities, CardAgentSessions.get(key))
            } finally {
              CardAgentSessions.clear()
              auth.close()
            }
          }
        }
      }
    }

  @Test
  fun confirmedOAuthRejectionsRemoveSavedCredentials() =
    runBlocking<Unit> {
      for ((status, error) in
        listOf(
          HttpStatusCode.BadRequest to "invalid_grant",
          HttpStatusCode.BadRequest to "invalid_request",
          HttpStatusCode.Unauthorized to "invalid_grant",
          HttpStatusCode.Forbidden to "access_denied",
        )) {
        val store = MemoryStore().apply { value = DesktopSavedSignIn("https://ha.test", "refresh") }
        val http = HttpClient(MockEngine { respond("""{"error":"$error"}""", status) })
        val auth = DesktopHomeAssistantAuth(store, http)
        try {
          auth.restore()
          assertNull(store.value)
          assertNull(auth.session.value)
          assertFailsWith<DesktopSignInExpired> { auth.currentAccessToken() }
        } finally {
          auth.close()
        }
      }
    }

  @Test
  fun signOutStillRemovesLocalCredentialsWhenHomeAssistantIsOffline() =
    runBlocking<Unit> {
      val store = MemoryStore().apply { value = DesktopSavedSignIn("https://ha.test", "refresh") }
      var offline = false
      val http =
        HttpClient(
          MockEngine {
            if (offline) throw java.io.IOException("Offline")
            respond(token())
          },
        )
      val auth = DesktopHomeAssistantAuth(store, http)
      val key = "https://ha.test:card-a"
      try {
        auth.restore()
        CardAgentSessions.put(
          key,
          SavedCardAgentSession("https://design.test", "owner", "request", Long.MAX_VALUE, "agent"),
        )
        offline = true
        auth.signOut()
        assertNull(store.value)
        assertNull(auth.session.value)
        assertNull(CardAgentSessions.get(key))
      } finally {
        CardAgentSessions.clear()
        auth.close()
      }
    }

  @Test
  fun malformedTokenResponsesNeverCreateASavedSession() =
    runBlocking<Unit> {
      val store = MemoryStore()
      var body = ""
      val http = HttpClient(MockEngine { respond(body) })
      val auth = DesktopHomeAssistantAuth(store, http, ::authorize, { DesktopOAuthCallback(0) })
      try {
        for (response in
          listOf(
            """{"access_token":null,"refresh_token":"refresh","expires_in":60}""",
            """{"access_token":"access","refresh_token":null,"expires_in":60}""",
            """{"access_token":"access","refresh_token":"refresh","expires_in":0}""",
          )) {
          body = response
          assertFailsWith<IllegalStateException> { auth.signIn("https://ha.test") }
          assertNull(store.value)
          assertNull(auth.session.value)
        }
      } finally {
        auth.close()
      }
    }

  @Test
  fun invalidServerNeverOpensBrowserOrSendsTokens() =
    runBlocking<Unit> {
      val store = MemoryStore()
      val http = HttpClient(MockEngine { error("No network for invalid URL") })
      val auth = DesktopHomeAssistantAuth(store, http, { error("No browser for invalid URL") })
      try {
        for (server in
          listOf("http://ha.test", "https://user:pass@ha.test", "https://ha.test/?secret=x")) {
          assertFailsWith<IllegalArgumentException> { auth.signIn(server) }
        }
        store.value = DesktopSavedSignIn("http://ha.test", "refresh")
        assertFailsWith<IllegalStateException> { auth.restore() }
      } finally {
        auth.close()
      }
    }
}
