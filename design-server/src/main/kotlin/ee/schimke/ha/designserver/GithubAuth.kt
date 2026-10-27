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
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GitHub sign-in for the design server's editing: who is designing, so the UI Builder designs they
 * seed are shared with them (`github:<login>`, as editor) and nobody else.
 *
 * Who signed in comes from [signIn]: the server's own OAuth App ([SignIn.OAuthApp]), or the sign-in
 * of the server it shares its host with ([SignIn.Host]).
 *
 * - [sessionSecret]: signs device tokens, and with an OAuth App its session and state cookies.
 * - [allowedUsers]: when not empty, only these logins may design.
 */
class GithubAuth(
  private val signIn: SignIn,
  sessionSecret: String,
  private val allowedUsers: Set<String> = emptySet(),
  private val http: HttpClient,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  constructor(
    clientId: String,
    clientSecret: String,
    publicUrl: String,
    sessionSecret: String,
    allowedUsers: Set<String> = emptySet(),
    http: HttpClient,
    clock: () -> Long = System::currentTimeMillis,
    authorizeUrl: String = "https://github.com/login/oauth/authorize",
    tokenUrl: String = "https://github.com/login/oauth/access_token",
    userUrl: String = "https://api.github.com/user",
  ) : this(
    SignIn.OAuthApp(clientId, clientSecret, publicUrl, authorizeUrl, tokenUrl, userUrl),
    sessionSecret,
    allowedUsers,
    http,
    clock,
  )

  /** Where who is signed in comes from. */
  sealed interface SignIn {
    /**
     * Plain OAuth web flow against a GitHub OAuth App whose callback is
     * `<publicUrl>/my/auth/github/callback`. No scope is requested: the login is all the server
     * needs. The session is a signed cookie (`login|expiry|HMAC`), so the server keeps no session
     * state. [publicUrl] is where browsers reach this server (`https://ha.coo.ee`).
     */
    data class OAuthApp(
      val clientId: String,
      val clientSecret: String,
      val publicUrl: String,
      val authorizeUrl: String = "https://github.com/login/oauth/authorize",
      val tokenUrl: String = "https://github.com/login/oauth/access_token",
      val userUrl: String = "https://api.github.com/user",
    ) : SignIn

    /**
     * The GitHub sign-in of the Compose Preview server this one shares its host with (ha.coo.ee's
     * `/auth/github`, signed in through preview.coo.ee): no OAuth App or cookie of its own.
     * Browsers sign in at `/auth/github/start`, and a request's login is that server's `GET
     * /auth/github/whoami`, asked at [url] (internal, e.g. `http://preview:8080`) with the
     * request's session cookie.
     */
    data class Host(val url: String) : SignIn
  }

  private val key = SecretKeySpec(sessionSecret.encodeToByteArray(), "HmacSHA256")

  private val secure = (signIn as? SignIn.OAuthApp)?.publicUrl?.startsWith("https://") == true

  /** The signed-in GitHub login of this browser, or null. */
  suspend fun login(call: ApplicationCall): String? =
    when (signIn) {
      is SignIn.OAuthApp -> {
        val session = call.request.cookies[SESSION_COOKIE]?.let(::verify)
        val login = session?.substringBefore('|')
        val expiry = session?.substringAfter('|', "")?.toLongOrNull()
        login?.takeIf { expiry != null && expiry > clock() && it.isNotEmpty() }
      }
      is SignIn.Host -> hostLogin(signIn, call)
    }?.takeIf(::allowed)

  /**
   * The login the host's sign-in names for [call]'s session cookie. Only that cookie is sent, and
   * only when the request has one, so anonymous requests (every app's manifest fetch) cost nothing.
   * Any failure reads as signed out.
   */
  private suspend fun hostLogin(host: SignIn.Host, call: ApplicationCall): String? {
    val session =
      call.request.cookies.rawCookies.filterKeys {
        it == HOST_COOKIE || it == "__Host-$HOST_COOKIE"
      }
    if (session.isEmpty()) return null
    return withTimeoutOrNull(HOST_TIMEOUT_MS) {
        runCatching {
          val reply =
            http.get("${host.url.trimEnd('/')}$HOST_WHOAMI_PATH") {
              header(
                HttpHeaders.Cookie,
                session.entries.joinToString("; ") { "${it.key}=${it.value}" },
              )
              accept(ContentType.Application.Json)
            }
          if (!reply.status.isSuccess()) return@runCatching null
          Json.parseToJsonElement(reply.bodyAsText())
            .jsonObject["login"]
            ?.jsonPrimitive
            ?.contentOrNull
        }
          .onFailure { System.err.println("sign-in: ${host.url} whoami failed: ${it.message}") }
          .getOrNull()
      }
      ?.takeIf { it.isNotEmpty() }
  }

  /**
   * A token the app sends as `Authorization: Bearer` to read [login]'s private designs: signed like
   * the session cookie, so it needs no storage, and good for a year.
   */
  fun deviceToken(login: String): String = sign("$DEVICE|$login|${clock() + DEVICE_TTL_MS}")

  /** The login a [deviceToken] was minted for, or null when forged, expired or not allowed. */
  fun deviceLogin(token: String): String? {
    val parts = verify(token)?.split('|') ?: return null
    if (parts.size != 3 || parts[0] != DEVICE) return null
    val expiry = parts[2].toLongOrNull() ?: return null
    return parts[1].takeIf { expiry > clock() && it.isNotEmpty() && allowed(it) }
  }

  /** The signed-in browser's login, or else the login of the request's device token. */
  suspend fun user(call: ApplicationCall): String? =
    login(call)
      ?: call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
        ?.let(::deviceLogin)

  /** Where to send a browser to sign in, returning to [returnPath] afterwards. */
  fun signInPath(returnPath: String): String =
    when (signIn) {
      is SignIn.OAuthApp -> START_PATH
      is SignIn.Host -> HOST_START_PATH
    } + "?return=${enc(safeReturn(returnPath))}"

  /** Where a sign-out form posts. The host's sign-out ends the sign-in on every host it reached. */
  val signOutPath: String
    get() =
      when (signIn) {
        is SignIn.OAuthApp -> SIGN_OUT_PATH
        is SignIn.Host -> "$HOST_SIGN_OUT_PATH?return=%2F"
      }

  fun routes(route: Route) {
    when (signIn) {
      is SignIn.OAuthApp -> oauthAppRoutes(route, signIn)
      // Links and checks that name this server's own sign-in still land on the host's.
      is SignIn.Host ->
        route.get(START_PATH) {
          call.respondRedirect(signInPath(call.request.queryParameters["return"] ?: "/my"))
        }
    }
  }

  private fun oauthAppRoutes(route: Route, app: SignIn.OAuthApp) {
    route.get(START_PATH) {
      val state = UiBuilderConfig.randomToken()
      val returnPath = safeReturn(call.request.queryParameters["return"])
      // The state is bound to this browser (a cookie) and to the return path, so a callback from
      // a sign-in this browser didn't start is refused.
      call.response.cookies.append(cookie(STATE_COOKIE, sign("$state|$returnPath"), maxAge = 600))
      call.respondRedirect(
        "${app.authorizeUrl}?client_id=${enc(app.clientId)}" +
          "&redirect_uri=${enc(app.callbackUrl())}&state=${enc(state)}&allow_signup=true"
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
        exchange(app, code)
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

  private suspend fun exchange(app: SignIn.OAuthApp, code: String): String {
    val token =
      http.submitForm(
        app.tokenUrl,
        parameters {
          append("client_id", app.clientId)
          append("client_secret", app.clientSecret)
          append("code", code)
          append("redirect_uri", app.callbackUrl())
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
      http.get(app.userUrl) {
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

  private fun SignIn.OAuthApp.callbackUrl() = "${publicUrl.trimEnd('/')}$CALLBACK_PATH"

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
    /**
     * Under `/my`, the person's own pages, never `/auth`: where the design server shares a hostname
     * with UI Builder (ha.coo.ee), the builder's sign-in owns `/auth`, and the edge routes `/my`
     * here.
     */
    const val START_PATH = "/my/auth/github"
    const val CALLBACK_PATH = "/my/auth/github/callback"
    const val SIGN_OUT_PATH = "/my/auth/sign-out"
    private const val SESSION_COOKIE = "design-server-session"
    private const val STATE_COOKIE = "design-server-oauth-state"
    private const val SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val DEVICE = "device"
    private const val DEVICE_TTL_MS = 365L * 24 * 60 * 60 * 1000

    /** The Compose Preview server's sign-in, on the host this server shares ([SignIn.Host]). */
    const val HOST_START_PATH = "/auth/github/start"
    const val HOST_WHOAMI_PATH = "/auth/github/whoami"
    const val HOST_SIGN_OUT_PATH = "/auth/github/logout"

    /** Its session cookie; `__Host-` prefixed over HTTPS. */
    private const val HOST_COOKIE = "cp_gh_auth"
    private const val HOST_TIMEOUT_MS = 5_000L

    /**
     * Only a same-site path: never an absolute URL a sign-in could be bounced to. Control
     * characters are refused too: browsers drop tabs and newlines while parsing a URL, so
     * `/\t/evil.example` would otherwise reach the `Location` header and resolve as
     * `//evil.example`.
     */
    fun safeReturn(path: String?): String =
      path?.takeIf {
        it.startsWith("/") &&
          !it.startsWith("//") &&
          '\\' !in it &&
          it.none { c -> c < ' ' || c == '\u007f' }
      } ?: "/"

    /**
     * From the environment, one of:
     * - `HOST_SIGN_IN_URL`: the internal URL of the Compose Preview server this one shares its host
     *   with, whose GitHub sign-in it reuses ([SignIn.Host]). `SESSION_SECRET` is optional: without
     *   it, the key that signs device tokens is generated once and kept in [keyDir].
     * - `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `PUBLIC_URL` and `SESSION_SECRET`: its own
     *   OAuth App ([SignIn.OAuthApp]).
     *
     * Plus optional `GITHUB_USERS` (comma-separated). Null when neither is set: the server then
     * gates editing with its printed edit link instead.
     */
    fun fromEnvironment(env: Map<String, String>, http: HttpClient, keyDir: File?): GithubAuth? {
      fun value(name: String) = env[name]?.takeIf { it.isNotBlank() }
      val hostUrl = value("HOST_SIGN_IN_URL")
      val clientId = value("GITHUB_CLIENT_ID")
      require(hostUrl == null || clientId == null) {
        "set HOST_SIGN_IN_URL or GITHUB_CLIENT_ID, not both: they are two ways to sign in"
      }
      val allowedUsers =
        value("GITHUB_USERS")
          .orEmpty()
          .split(',')
          .map { it.trim() }
          .filter { it.isNotEmpty() }
          .toSet()
      if (hostUrl != null) {
        val secret =
          value("SESSION_SECRET")
            ?: storedKey(
              requireNotNull(keyDir) {
                "HOST_SIGN_IN_URL is set, so SESSION_SECRET or USER_DESIGNS_DIR is needed too"
              }
            )
        return GithubAuth(SignIn.Host(hostUrl), secret, allowedUsers, http)
      }
      if (clientId == null) return null
      fun required(name: String) =
        requireNotNull(value(name)) { "GITHUB_CLIENT_ID is set, so $name is needed too" }
      return GithubAuth(
        clientId = clientId,
        clientSecret = required("GITHUB_CLIENT_SECRET"),
        publicUrl = required("PUBLIC_URL"),
        sessionSecret = required("SESSION_SECRET"),
        allowedUsers = allowedUsers,
        http = http,
      )
    }

    /**
     * The device-token key kept in [dir], made on first use and readable by this user only. Losing
     * it only means signing in again for a new device token.
     */
    internal fun storedKey(dir: File): String {
      val file = File(dir, KEY_FILE)
      if (!file.isFile) {
        dir.mkdirs()
        // Created owner-only, never readable for a moment before a chmod.
        val fresh = File(dir, "$KEY_FILE.tmp").toPath()
        Files.deleteIfExists(fresh)
        Files.createFile(
          fresh,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        Files.writeString(fresh, UiBuilderConfig.randomToken() + UiBuilderConfig.randomToken())
        Files.move(fresh, file.toPath(), StandardCopyOption.ATOMIC_MOVE)
      }
      return file.readText().trim().also { check(it.length >= 32) { "$file is too short" } }
    }

    private const val KEY_FILE = "device-token-key"
  }
}
