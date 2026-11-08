package ee.schimke.terrazzo.desktop

import ee.schimke.terrazzo.shared.validServerUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class DesktopAuthSession(val server: String)

internal class DesktopSignInExpired : IllegalStateException("Sign in to Home Assistant again.")

internal class DesktopCredentialStoreUnavailable :
  IllegalStateException("The credential store is unavailable.")

internal class DesktopHomeAssistantAuth(
  private val store: DesktopCredentialStore = KeyringDesktopCredentialStore(),
  private val http: HttpClient = HttpClient {
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 30_000 }
  },
  private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
  private val callbackFactory: () -> DesktopOAuthCallback = { DesktopOAuthCallback() },
  private val now: () -> Long = System::currentTimeMillis,
  private val signInTimeoutMillis: Long = 180_000,
) : AutoCloseable {
  private val mutex = Mutex()
  private val mutableSession = MutableStateFlow<DesktopAuthSession?>(null)
  val session = mutableSession.asStateFlow()
  private var saved: DesktopSavedSignIn? = null
  private var accessToken: String? = null
  private var expiresAt = 0L

  suspend fun restore() =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val value = store.read() ?: return@withLock
        check(validServerUrl(value.server) && value.refreshToken.isNotBlank())
        saved = value
        try {
          refresh(value)
          mutableSession.value = DesktopAuthSession(value.server)
        } catch (_: DesktopSignInExpired) {
          // Rejected credentials have already been removed; show the connection screen.
        }
      }
    }

  suspend fun signIn(server: String) =
    withContext(Dispatchers.IO) {
      require(validServerUrl(server))
      callbackFactory().use { callback ->
        val authorization =
          URLBuilder("$server/auth/authorize")
            .apply {
              parameters.append("response_type", "code")
              parameters.append("client_id", DESKTOP_CLIENT_ID)
              parameters.append("redirect_uri", callback.redirectUri)
              parameters.append("state", callback.state)
              parameters.append("code_challenge", pkceChallenge(callback.verifier))
              parameters.append("code_challenge_method", "S256")
            }
            .buildString()
        val code =
          withTimeout(signInTimeoutMillis) {
            openBrowser(URI(authorization))
            callback.awaitCode()
          }
        mutex.withLock {
          val token =
            requestToken(
              server,
              Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", DESKTOP_CLIENT_ID)
                append("redirect_uri", callback.redirectUri)
                append("code", code)
                append("code_verifier", callback.verifier)
              },
            )
          val value = DesktopSavedSignIn(server, checkNotNull(token.refreshToken))
          try {
            store.write(value)
          } catch (_: Exception) {
            revoke(value)
            throw DesktopCredentialStoreUnavailable()
          }
          saved = value
          useToken(token)
          mutableSession.value = DesktopAuthSession(server)
        }
      }
    }

  suspend fun currentAccessToken(): String =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val value = saved ?: throw DesktopSignInExpired()
        if (accessToken == null || now() >= expiresAt - 30_000) refresh(value)
        checkNotNull(accessToken)
      }
    }

  suspend fun signOut() =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val value = saved ?: return@withLock
        // A failed local deletion must leave the session available so the user can retry.
        store.clear()
        saved = null
        accessToken = null
        // Local sign-out works offline. HA also lets the user revoke sessions from their profile.
        try {
          revoke(value)
        } finally {
          mutableSession.value = null
        }
      }
    }

  private suspend fun revoke(value: DesktopSavedSignIn) {
    withTimeoutOrNull(5_000) {
      try {
        http.submitForm(
          "${value.server}/auth/token",
          Parameters.build {
            append("action", "revoke")
            append("token", value.refreshToken)
          },
        )
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Best-effort server revocation after removing the saved local sign-in.
      }
    }
  }

  private suspend fun refresh(value: DesktopSavedSignIn) {
    val token =
      try {
        requestToken(
          value.server,
          Parameters.build {
            append("grant_type", "refresh_token")
            append("client_id", DESKTOP_CLIENT_ID)
            append("refresh_token", value.refreshToken)
          },
        )
      } catch (e: DesktopSignInExpired) {
        try {
          store.clear()
        } finally {
          saved = null
          accessToken = null
          mutableSession.value = null
        }
        throw e
      }
    // Home Assistant normally retains refresh tokens. Preserve a rotation if a server returns one.
    if (token.refreshToken != null && token.refreshToken != value.refreshToken) {
      val rotated = DesktopSavedSignIn(value.server, token.refreshToken)
      store.write(rotated)
      saved = rotated
    }
    useToken(token)
  }

  private class Token(val accessToken: String, val refreshToken: String?, val expiresIn: Long)

  private suspend fun requestToken(server: String, parameters: Parameters): Token {
    val response = http.submitForm("$server/auth/token", parameters)
    if (response.status.value in listOf(400, 401, 403)) throw DesktopSignInExpired()
    check(response.status.isSuccess()) { "Home Assistant sign-in is unavailable." }
    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val access =
      checkNotNull(body["access_token"]?.jsonPrimitive?.contentOrNull).also {
        check(it.isNotBlank())
      }
    val refresh = body["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val expires = checkNotNull(body["expires_in"]?.jsonPrimitive?.longOrNull)
    check(expires in 1..86400)
    return Token(access, refresh, expires)
  }

  private fun useToken(token: Token) {
    accessToken = token.accessToken
    expiresAt = now() + token.expiresIn * 1000
  }

  override fun close() {
    http.close()
  }
}
