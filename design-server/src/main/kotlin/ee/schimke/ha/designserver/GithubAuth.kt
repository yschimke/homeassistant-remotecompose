package ee.schimke.ha.designserver

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GitHub sign-in for the design server's editing: who is designing, so the UI Builder designs they
 * seed are shared with them (`github:<login>`, as editor) and nobody else.
 *
 * Plain OAuth web flow against a GitHub OAuth App whose callback is
 * `<publicUrl>/auth/github/callback`. No scope is requested: the login is all the server needs. The
 * session is a signed cookie (`login|expiry|HMAC`), so the server keeps no session state.
 *
 * - [clientId], [clientSecret]: the OAuth App's.
 * - [publicUrl]: where browsers reach this server (`https://ha.coo.ee`).
 * - [sessionSecret]: signs session and state cookies.
 * - [allowedUsers]: when not empty, only these logins may design.
 */
class GithubAuth(
  private val clientId: String,
  private val clientSecret: String,
  private val publicUrl: String,
  sessionSecret: String,
  private val allowedUsers: Set<String> = emptySet(),
  private val http: HttpClient,
  private val clock: () -> Long = System::currentTimeMillis,
  private val authorizeUrl: String = "https://github.com/login/oauth/authorize",
  private val tokenUrl: String = "https://github.com/login/oauth/access_token",
  private val userUrl: String = "https://api.github.com/user",
) {
  private val key = SecretKeySpec(sessionSecret.encodeToByteArray(), "HmacSHA256")

  private val secure = publicUrl.startsWith("https://")

  /** The signed-in GitHub login of this browser, or null. */
  fun login(call: ApplicationCall): String? {
    val session = call.request.cookies[SESSION_COOKIE]?.let(::verify) ?: return null
    val login = session.substringBefore('|')
    val expiry = session.substringAfter('|', "").toLongOrNull() ?: return null
    return login.takeIf { expiry > clock() && it.isNotEmpty() && allowed(it) }
  }

  /** Where to send a browser to sign in, returning to [returnPath] afterwards. */
  fun signInPath(returnPath: String): String =
    "$START_PATH?return=${URLEncoder.encode(safeReturn(returnPath), Charsets.UTF_8)}"

  fun routes(route: Route) {
    route.get(START_PATH) {
      val state = UiBuilderConfig.randomToken()
      val returnPath = safeReturn(call.request.queryParameters["return"])
      // The state is bound to this browser (a cookie) and to the return path, so a callback from
      // a sign-in this browser didn't start is refused.
      call.response.cookies.append(cookie(STATE_COOKIE, sign("$state|$returnPath"), maxAge = 600))
      call.respondRedirect(
        "$authorizeUrl?client_id=${enc(clientId)}" +
          "&redirect_uri=${enc(callbackUrl())}&state=${enc(state)}&allow_signup=true"
      )
    }

    route.get(CALLBACK_PATH) {
      val params = call.request.queryParameters
      val stored = call.request.cookies[STATE_COOKIE]?.let(::verify)
      val state = stored?.substringBefore('|')
      val returnPath = stored?.substringAfter('|', "/") ?: "/"
      call.response.cookies.append(cookie(STATE_COOKIE, "", maxAge = 0))
      if (state == null || params["state"] != state) {
        return@get call.respondText(
          "sign-in expired or was not started here; try again",
          status = HttpStatusCode.BadRequest,
        )
      }
      val code =
        params["code"]
          ?: return@get call.respondText(
            "GitHub did not sign you in: ${params["error_description"] ?: params["error"]}",
            status = HttpStatusCode.Forbidden,
          )
      val login = runCatching {
        exchange(code)
      }
        .getOrElse {
          return@get call.respondText(
            "GitHub sign-in failed: ${it.message}",
            status = HttpStatusCode.BadGateway,
          )
        }
      if (!allowed(login)) {
        return@get call.respondText(
          "$login may not design on this server",
          status = HttpStatusCode.Forbidden,
        )
      }
      val expiry = clock() + SESSION_TTL_MS
      call.response.cookies.append(
        cookie(SESSION_COOKIE, sign("$login|$expiry"), maxAge = (SESSION_TTL_MS / 1000).toInt())
      )
      call.respondRedirect(returnPath)
    }

    route.post(SIGN_OUT_PATH) {
      call.response.cookies.append(cookie(SESSION_COOKIE, "", maxAge = 0))
      call.respondRedirect("/")
    }
  }

  private suspend fun exchange(code: String): String {
    val token =
      http.submitForm(
        tokenUrl,
        parameters {
          append("client_id", clientId)
          append("client_secret", clientSecret)
          append("code", code)
          append("redirect_uri", callbackUrl())
        },
      ) {
        accept(ContentType.Application.Json)
      }
    check(token.status.isSuccess()) { "token exchange answered ${token.status}" }
    val body = Json.parseToJsonElement(token.bodyAsText()).jsonObject
    val accessToken =
      body["access_token"]?.jsonPrimitive?.contentOrNull
        ?: error(body["error_description"]?.jsonPrimitive?.contentOrNull ?: "no access token")
    val user =
      http.get(userUrl) {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        accept(ContentType.parse("application/vnd.github+json"))
      }
    check(user.status.isSuccess()) { "GitHub user lookup answered ${user.status}" }
    return Json.parseToJsonElement(user.bodyAsText())
      .jsonObject["login"]
      ?.jsonPrimitive
      ?.contentOrNull ?: error("GitHub returned no login")
  }

  private fun allowed(login: String) =
    allowedUsers.isEmpty() || allowedUsers.any { it.equals(login, ignoreCase = true) }

  private fun callbackUrl() = "${publicUrl.trimEnd('/')}$CALLBACK_PATH"

  private fun cookie(name: String, value: String, maxAge: Int) =
    Cookie(
      name,
      value,
      maxAge = maxAge,
      path = "/",
      secure = secure,
      httpOnly = true,
      extensions = mapOf("SameSite" to "Lax"),
    )

  /** `payload.signature`, base64url. */
  internal fun sign(payload: String): String {
    val encoded = b64(payload.encodeToByteArray())
    return "$encoded.${b64(mac(encoded))}"
  }

  /** The payload of a value [sign] made; null when forged or garbled. */
  internal fun verify(value: String): String? {
    val encoded = value.substringBefore('.', "")
    val signature = value.substringAfter('.', "")
    if (encoded.isEmpty() || signature.isEmpty()) return null
    val expected = b64(mac(encoded))
    if (!MessageDigest.isEqual(expected.encodeToByteArray(), signature.encodeToByteArray()))
      return null
    return runCatching { Base64.getUrlDecoder().decode(encoded).decodeToString() }.getOrNull()
  }

  private fun mac(data: String): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data.encodeToByteArray())

  private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

  companion object {
    const val START_PATH = "/auth/github"
    const val CALLBACK_PATH = "/auth/github/callback"
    const val SIGN_OUT_PATH = "/auth/sign-out"
    private const val SESSION_COOKIE = "design-server-session"
    private const val STATE_COOKIE = "design-server-oauth-state"
    private const val SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** Only a same-site path: never an absolute URL a sign-in could be bounced to. */
    fun safeReturn(path: String?): String =
      path?.takeIf { it.startsWith("/") && !it.startsWith("//") && '\\' !in it } ?: "/"

    /**
     * From the environment: `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `PUBLIC_URL` and
     * `SESSION_SECRET`, plus optional `GITHUB_USERS` (comma-separated). Null when the client id is
     * unset: the server then gates editing with its printed edit link instead.
     */
    fun fromEnvironment(env: Map<String, String>, http: HttpClient): GithubAuth? {
      val clientId = env["GITHUB_CLIENT_ID"]?.takeIf { it.isNotBlank() } ?: return null
      fun required(name: String) =
        requireNotNull(env[name]?.takeIf { it.isNotBlank() }) {
          "GITHUB_CLIENT_ID is set, so $name is needed too"
        }
      return GithubAuth(
        clientId = clientId,
        clientSecret = required("GITHUB_CLIENT_SECRET"),
        publicUrl = required("PUBLIC_URL"),
        sessionSecret = required("SESSION_SECRET"),
        allowedUsers =
          env["GITHUB_USERS"]
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
        http = http,
      )
    }
  }
}
