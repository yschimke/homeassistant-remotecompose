package ee.schimke.terrazzo.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
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
 */
@SingleIn(AppScope::class)
@Inject
class HaAuthService(context: Context) {

    // Allow HTTP for the integration Docker container. AppAuth's
    // default ConnectionBuilder rejects non-HTTPS endpoints at the
    // token-exchange step; HA on 10.0.2.2:8124 is plain HTTP.
    private val appAuth = AuthorizationService(
        context,
        AppAuthConfiguration.Builder()
            .setConnectionBuilder(PlainHttpConnectionBuilder)
            .build(),
    )

    fun close() = appAuth.dispose()

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

    companion object {
        const val CLIENT_ID = "https://yschimke.github.io/homeassistant-remotecompose/terrazzo-auth/index.html"
        const val REDIRECT_URI = "rcha://auth-callback"
    }
}

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
