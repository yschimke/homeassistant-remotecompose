package ee.schimke.terrazzo.desktop

import com.sun.net.httpserver.HttpServer
import io.ktor.http.parseQueryString
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred

/** A stable loopback client ID needs no public redirect declaration or OS scheme registration. */
internal const val DESKTOP_AUTH_PORT = 43823
internal const val DESKTOP_CLIENT_ID = "http://127.0.0.1:$DESKTOP_AUTH_PORT/"

internal fun oauthNonce(): String =
  Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

internal fun pkceChallenge(verifier: String): String =
  Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(
      MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
    )

/** Listens only during sign-in. Bad or unrelated requests cannot consume the pending callback. */
internal class DesktopOAuthCallback(port: Int = DESKTOP_AUTH_PORT) : AutoCloseable {
  val state = oauthNonce()
  val verifier = oauthNonce()
  private val code = CompletableDeferred<String>()
  private val server =
    HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
  val origin = "http://127.0.0.1:${server.address.port}/"
  val redirectUri = "${origin}auth/callback"

  init {
    server.createContext("/") { exchange ->
      exchange.use {
        val params = runCatching {
          parseQueryString(exchange.requestURI.rawQuery.orEmpty())
        }
          .getOrNull()
        val valid =
          exchange.requestMethod == "GET" &&
            exchange.requestURI.path == "/auth/callback" &&
            exchange.requestHeaders.getFirst("Host") == "127.0.0.1:${server.address.port}" &&
            params?.getAll("state") == listOf(state) &&
            !code.isCompleted
        val authorizationCode = params?.getAll("code")?.singleOrNull()?.takeIf { it.isNotBlank() }
        val denied = params?.getAll("error")?.singleOrNull() != null
        val accepted = valid && (authorizationCode != null || denied)
        val body =
          if (accepted && !denied)
            "Sign-in received. You can close this tab and return to Terrazzo to finish connecting."
          else if (accepted) "Sign-in was declined. Return to Terrazzo to try again."
          else "This sign-in callback is invalid. Return to Terrazzo and try again."
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(if (accepted) 200 else 400, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        if (accepted) {
          if (denied) code.completeExceptionally(IllegalStateException("Sign-in was declined."))
          else code.complete(checkNotNull(authorizationCode))
        }
      }
    }
    server.start()
  }

  suspend fun awaitCode(): String = code.await()

  override fun close() {
    code.cancel()
    server.stop(0)
  }
}
