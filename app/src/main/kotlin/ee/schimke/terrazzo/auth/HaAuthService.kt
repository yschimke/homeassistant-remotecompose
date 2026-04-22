package ee.schimke.terrazzo.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse

/**
 * OAuth flow against a Home Assistant instance.
 *
 * HA speaks IndieAuth-flavored OAuth 2.0:
 *   - `client_id` is a URL that hosts a `<link rel="redirect_uri">`
 *     whitelisting the app's custom scheme. Terrazzo's client_id is
 *     `https://yschimke.github.io/homeassistant-remotecompose/auth/`.
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
 * One instance per HA host. The refresh token is handed to [TokenVault]
 * for persistence; the access token is returned to the caller and held
 * in memory for the WebSocket connection lifetime.
 */
class HaAuthService(private val context: Context) {

    private val appAuth = AuthorizationService(context)

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
        /**
         * Pinned to a stable URL we control. Points at the IndieAuth
         * page under `docs/auth/` in the main repo, published via
         * GitHub Pages.
         */
        const val CLIENT_ID = "https://yschimke.github.io/homeassistant-remotecompose/auth/"
        const val REDIRECT_URI = "rcha://auth-callback"
    }
}
