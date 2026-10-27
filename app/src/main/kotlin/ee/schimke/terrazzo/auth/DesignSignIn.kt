package ee.schimke.terrazzo.auth

import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import ee.schimke.terrazzo.LocalTerrazzoGraph
import java.util.Base64
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

/**
 * Signs the app in to a card design server with GitHub and keeps its device token, so nobody copies
 * one from the server's `/my` page:
 * ```
 * val signIn = rememberDesignSignIn(onError = { … })
 * signIn(designServerUrl)   // Custom Tab: GitHub, then "Connect harc"
 * ```
 *
 * The server's `/my/oauth` flow redirects to `rcha://auth-callback` like Home Assistant's sign-in
 * ([LoginController]); the code is exchanged (with PKCE) for the device token, which is saved as
 * the design token.
 */
@Composable
fun rememberDesignSignIn(onError: (Throwable) -> Unit = {}): (designServerUrl: String) -> Unit {
  val graph = LocalTerrazzoGraph.current
  val authService = graph.authService
  val prefs = graph.preferencesStore
  val scope = rememberCoroutineScope()
  val currentOnError by rememberUpdatedState(onError)
  val onResult: (String, ActivityResult) -> Unit =
    remember(scope) {
      { _, result ->
        val data = result.data
        val failure = data?.let(AuthorizationException::fromIntent)
        val response = data?.let(AuthorizationResponse::fromIntent)
        if (failure != null) {
          Log.w(TAG, "design sign-in failed: ${failure.errorDescription}", failure)
          currentOnError(failure)
        } else if (response != null) {
          // No response and no failure: the tab was closed, which needs no message.
          scope.launch {
            runCatching { authService.exchangeCode(response) }
              .mapCatching { checkNotNull(it.accessToken) { "the server sent no token" } }
              .onSuccess { prefs.setDesignToken(it) }
              .onFailure {
                Log.w(TAG, "design token exchange failed", it)
                currentOnError(it)
              }
          }
        }
      }
    }
  val launch = rememberKeyedLauncher(onResult)
  return remember(authService, launch) {
    { url: String -> launch(url, authService.prepareDesignSignInIntent(url)) }
  }
}

/**
 * The GitHub login a design server's device token was made for, read from its payload
 * (`device|<login>|<expiry>`, base64url before the signature), or null for a token pasted from
 * elsewhere. Only for showing who is signed in: the server checks the signature.
 */
fun designTokenLogin(token: String): String? = runCatching {
  val payload =
    Base64.getUrlDecoder().decode(token.substringBefore('.')).decodeToString().split('|')
  payload.takeIf { it.size == 3 && it[0] == "device" }?.get(1)?.takeIf { it.isNotBlank() }
}
  .getOrNull()

private const val TAG = "DesignSignIn"
