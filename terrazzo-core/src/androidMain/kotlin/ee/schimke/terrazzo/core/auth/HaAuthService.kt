package ee.schimke.terrazzo.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.network.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import net.openid.appauth.connectivity.ConnectionBuilder

/**
 * OAuth flow against a Home Assistant instance.
 *
 * HA speaks IndieAuth-flavored OAuth 2.0:
 *   - `client_id` is a URL that hosts a `<link rel="redirect_uri">`
 *     whitelisting the app's custom scheme. HA fetches it server-side
 *     so the URL has to be reachable from the HA process, not the app.
 *     See [CLIENT_ID] for the current (integration-local) value.
 *   - `redirect_uri` is `rcha://auth-callback` (declared in manifest,
 *     handled by `net.openid.appauth.RedirectUriReceiverActivity`).
 *   - Authorization endpoint: `{baseUrl}/auth/authorize`
 *   - Token endpoint: `{baseUrl}/auth/token` (grant_type =
 *     `authorization_code` for the initial exchange, `refresh_token`
 *     for rotation).
 *   - No PKCE — HA doesn't support it yet (community.home-assistant.io
 *     thread 838269). AppAuth still launches the auth URL in a Custom
 *     Tab, intercepts via the manifest intent filter, and exchanges
 *     the code for tokens over plain HTTPS form-POST.
 *
 * Scoped to the graph — one `AuthorizationService` per process. The
 * refresh token is handed to [TokenVault] for persistence; the access
 * token is returned to the caller and held in memory for the
 * WebSocket connection lifetime.
 *
 * Refresh path: [refreshAccessToken] bypasses AppAuth and posts the
 * `grant_type=refresh_token` form directly through Ktor on top of the
 * shared [HttpEngineFactory] OkHttp engine. That means
 * `RemoteUrlInterceptor` + `LanConnectionPolicyInterceptor` see the
 * request and can rewrite a LAN-only base URL to the instance's public
 * URL when the device is away from home. AppAuth's
 * `performTokenRequest` runs on its own `HttpURLConnection` and can't
 * be re-routed without a heavier `ConnectionBuilder` bridge — initial
 * sign-in still uses that path, but the user is usually on the LAN
 * they just typed the URL for, so it's not a blocker.
 */
@SingleIn(AppScope::class)
@Inject
class HaAuthService(context: Context, httpEngineFactory: HttpEngineFactory) {

    // Allow HTTP for the integration Docker container. AppAuth's
    // default ConnectionBuilder rejects non-HTTPS endpoints at the
    // token-exchange step; HA on 10.0.2.2:8124 is plain HTTP.
    private val appAuth = AuthorizationService(
        context,
        AppAuthConfiguration.Builder()
            .setConnectionBuilder(PlainHttpConnectionBuilder)
            .build(),
    )

    // Shares the engine (and its interceptor chain) with HaClient /
    // AddonClient so token refresh participates in the same URL-rewrite
    // + LAN-policy gating. Cheap — Ktor reuses the OkHttp dispatcher /
    // connection pool from the engine.
    private val httpClient: HttpClient = HttpClient(httpEngineFactory.engine)

    fun close() {
        appAuth.dispose()
        httpClient.close()
    }

    /**
     * Build the pending-intent payload an Activity uses to launch the
     * sign-in flow. Caller invokes
     * `startActivityForResult(intent, REQ_CODE)`; AppAuth fills the
     * result extras so `AuthorizationResponse.fromIntent(data)` works.
     */
    fun prepareAuthIntent(baseUrl: String): Intent {
        val config = AuthorizationServiceConfiguration(
            Uri.parse("$baseUrl/auth/authorize"),
            Uri.parse("$baseUrl/auth/token"),
        )
        val request = AuthorizationRequest.Builder(
            config,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        ).build()
        return appAuth.getAuthorizationRequestIntent(request)
    }

    /**
     * Exchange the authorization code for an access + refresh token.
     * Call after the redirect intent lands in the Activity.
     */
    suspend fun exchangeCode(response: AuthorizationResponse): TokenResponse =
        suspendCancellableCoroutine { cont ->
            val tokenRequest = response.createTokenExchangeRequest()
            appAuth.performTokenRequest(tokenRequest) { tokens, ex ->
                when {
                    tokens != null -> cont.resume(tokens)
                    ex != null -> cont.resumeWithException(ex)
                    else -> cont.resumeWithException(AuthorizationException.GeneralErrors.NETWORK_ERROR)
                }
            }
        }

    /**
     * Mint a fresh access token from a stored refresh token. Used on
     * cold-start auto-resume so the app upgrades from the cache-only
     * stub session to a live one without sending the user back through
     * the Custom Tab. HA usually doesn't rotate the refresh token on
     * this exchange, but if it does the response carries the new one.
     *
     * Goes through the shared OkHttp engine so the request is gated by
     * `LanConnectionPolicyInterceptor` and rewritten to the public URL
     * by `RemoteUrlInterceptor` when the device can't reach the LAN
     * base URL (e.g. cellular, away from home).
     */
    suspend fun refreshAccessToken(baseUrl: String, refreshToken: String): RefreshedTokens {
        val tokenUrl = "${baseUrl.trim().removeSuffix("/")}/auth/token"
        val response = httpClient.submitForm(
            url = tokenUrl,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", CLIENT_ID)
            },
        )
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("HA token refresh failed: ${response.status} $body")
        }
        return parseTokenResponse(body)
    }

    companion object {
        const val CLIENT_ID = "https://yschimke.github.io/homeassistant-remotecompose/terrazzo-auth/index.html"
        const val REDIRECT_URI = "rcha://auth-callback"

        private val tokenJson = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseTokenResponse(body: String): RefreshedTokens {
            val obj: JsonObject = tokenJson.parseToJsonElement(body).jsonObject
            return RefreshedTokens(
                accessToken = obj.stringOrNull("access_token"),
                refreshToken = obj.stringOrNull("refresh_token"),
                expiresInSeconds = obj["expires_in"]?.let {
                    (it as? JsonPrimitive)?.content?.toLongOrNull()
                },
                tokenType = obj.stringOrNull("token_type"),
            )
        }

        private fun JsonObject.stringOrNull(key: String): String? = when (val v = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.content
            else -> null
        }
    }
}

/**
 * Result of a refresh-token exchange. Fields mirror the OAuth response shape so the call site
 * can read `accessToken` and (optionally) `refreshToken` if HA rotated it.
 */
data class RefreshedTokens(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
    val tokenType: String?,
)

/**
 * AppAuth `ConnectionBuilder` that accepts both HTTPS and plain HTTP.
 *
 * Production HA deployments almost always speak HTTPS (via reverse proxy
 * or Nabu Casa), but the integration Docker container talks plain HTTP
 * on loopback. Without this, the token exchange step throws
 * `only https connections are permitted`.
 */
private object PlainHttpConnectionBuilder : ConnectionBuilder {
    override fun openConnection(uri: Uri): HttpURLConnection {
        val url = URL(uri.toString())
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = false
        if (conn is HttpsURLConnection) {
            // Defaults are fine for real HTTPS; no custom trust manager.
        }
        return conn
    }
}
