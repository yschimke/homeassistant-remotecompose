package ee.schimke.ha.designserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Signing the app in with a tap rather than a device token copied from `/my`: a minimal OAuth 2.0
 * authorization-code flow for a native app (RFC 8252), with PKCE (RFC 7636), whose access token is
 * a device token ([GithubAuth.deviceToken]). The app drives it with AppAuth in a Custom Tab, as it
 * does Home Assistant's sign-in, and gets the redirect at the same `rcha://auth-callback`.
 * - `GET /my/oauth/authorize` (`response_type=code`, `client_id`, `redirect_uri`, `state`,
 *   `code_challenge`, `code_challenge_method=S256`): signs the browser in with GitHub first, then
 *   asks once, "Connect harc as <login>?". Its button POSTs back here.
 * - `POST /my/oauth/authorize`: same origin only ([isCrossOrigin]), mints a code and redirects to
 *   `redirect_uri?code=…&state=…`.
 * - `POST /my/oauth/token` (`grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`,
 *   `code_verifier`): the device token, as `{"access_token", "token_type", "expires_in"}`.
 *
 * Only [REDIRECT_URIS] are accepted, and S256 PKCE is required: another app that registers the same
 * scheme and catches the redirect still can't redeem the code without the verifier that never left
 * the app that started the flow. A code is spent on its first use, whatever the outcome, and lives
 * [CODE_TTL_MS].
 */
class AppSignIn(
  private val github: GithubAuth,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private class Grant(
    val login: String,
    val clientId: String,
    val redirectUri: String,
    val challenge: String,
    val expiresAt: Long,
  )

  private val grants = ConcurrentHashMap<String, Grant>()

  /** A valid authorization request's parameters, or why it isn't one. */
  private class Request(
    val clientId: String,
    val redirectUri: String,
    val state: String,
    val challenge: String,
  )

  private fun request(params: Parameters): Result<Request> = runCatching {
    require(params["response_type"] == "code") { "response_type must be code" }
    val clientId = params["client_id"].orEmpty()
    require(clientId.isNotEmpty() && clientId.length <= 200) { "client_id is required" }
    val redirectUri = params["redirect_uri"].orEmpty()
    require(redirectUri in REDIRECT_URIS) { "redirect_uri is not one this server sends to" }
    val state = params["state"].orEmpty()
    require(state.isNotEmpty() && state.length <= 200) { "state is required" }
    require(params["code_challenge_method"] == "S256") { "PKCE with S256 is required" }
    val challenge = params["code_challenge"].orEmpty()
    require(CHALLENGE.matches(challenge)) { "code_challenge is required" }
    Request(clientId, redirectUri, state, challenge)
  }

  fun routes(route: Route) {
    route.get(AUTHORIZE_PATH) {
      call.response.header(HttpHeaders.CacheControl, "no-store")
      request(call.request.queryParameters).exceptionOrNull()?.let {
        return@get call.respondText(
          "This sign-in link is broken: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
      }
      val login =
        github.login(call) ?: return@get call.respondRedirect(github.signInPath(call.request.uri))
      // The form carries the request on, and POST checks it again.
      call.respondText(consentPage(login, call.request.queryParameters), ContentType.Text.Html)
    }

    route.post(AUTHORIZE_PATH) {
      if (call.isCrossOrigin())
        return@post call.respondText(
          "same-origin request required",
          status = HttpStatusCode.Forbidden,
        )
      val params = call.receiveParameters()
      val request =
        request(params).getOrElse {
          return@post call.respondText(
            "This sign-in link is broken: ${it.message}",
            status = HttpStatusCode.BadRequest,
          )
        }
      val login =
        github.login(call)
          ?: return@post call.respondText(
            "sign in with GitHub first",
            status = HttpStatusCode.Unauthorized,
          )
      val code = mint(login, request)
      call.response.header(HttpHeaders.CacheControl, "no-store")
      call.response.header("Referrer-Policy", "no-referrer")
      call.respondRedirect(
        URLBuilder(request.redirectUri)
          .apply {
            parameters.append("code", code)
            parameters.append("state", request.state)
          }
          .buildString()
      )
    }

    route.post(TOKEN_PATH) {
      call.response.header(HttpHeaders.CacheControl, "no-store")
      val params = call.receiveParameters()
      if (params["grant_type"] != "authorization_code")
        return@post call.oauthError("unsupported_grant_type")
      val grant = params["code"]?.let { grants.remove(it) } ?: return@post call.oauthError()
      val verifier = params["code_verifier"].orEmpty()
      if (
        grant.expiresAt <= clock() ||
          params["redirect_uri"] != grant.redirectUri ||
          params["client_id"] != grant.clientId ||
          !VERIFIER.matches(verifier) ||
          !tokensMatch(grant.challenge, challengeOf(verifier))
      )
        return@post call.oauthError()
      call.respondText(
        """{"access_token":"${github.deviceToken(grant.login)}","token_type":"Bearer",""" +
          """"expires_in":${GithubAuth.DEVICE_TTL_MS / 1000}}""",
        ContentType.Application.Json,
      )
    }
  }

  private fun mint(login: String, request: Request): String {
    val now = clock()
    grants.values.removeIf { it.expiresAt <= now }
    check(grants.size < MAX_GRANTS) { "too many sign-ins in flight" }
    val code = UiBuilderConfig.randomToken()
    grants[code] =
      Grant(login, request.clientId, request.redirectUri, request.challenge, now + CODE_TTL_MS)
    return code
  }

  private suspend fun ApplicationCall.oauthError(error: String = "invalid_grant") =
    respondText(
      """{"error":"$error"}""",
      ContentType.Application.Json,
      HttpStatusCode.BadRequest,
    )

  private fun consentPage(login: String, params: Parameters): String = buildString {
    val esc = Pages::esc
    append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>Connect harc</title></head><body>")
    append("<h1>Connect harc</h1>")
    append("<p>harc will read and publish your private card designs as <b>${esc(login)}</b>.</p>")
    append("<form method=\"post\" action=\"$AUTHORIZE_PATH\">")
    for (name in FORWARDED) {
      params[name]?.let { append("<input type=\"hidden\" name=\"$name\" value=\"${esc(it)}\">") }
    }
    append("<button>Connect</button></form>")
    append("<p><a href=\"/my\">Not now</a></p>")
    append("</body></html>")
  }

  companion object {
    const val AUTHORIZE_PATH = "/my/oauth/authorize"
    const val TOKEN_PATH = "/my/oauth/token"

    /** Where the app's AppAuth listens: the redirect it uses for Home Assistant too. */
    val REDIRECT_URIS = setOf("rcha://auth-callback")

    internal const val CODE_TTL_MS = 2 * 60 * 1000L
    private const val MAX_GRANTS = 1_000

    private val FORWARDED =
      listOf(
        "response_type",
        "client_id",
        "redirect_uri",
        "state",
        "code_challenge",
        "code_challenge_method",
      )

    /** base64url SHA-256: 43 characters. */
    private val CHALLENGE = Regex("[A-Za-z0-9_-]{43}")

    /** RFC 7636's code_verifier. */
    private val VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")

    internal fun challengeOf(verifier: String): String =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
  }
}
