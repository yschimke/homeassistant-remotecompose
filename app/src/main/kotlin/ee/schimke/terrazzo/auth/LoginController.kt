package ee.schimke.terrazzo.auth

import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.TokenVault
import ee.schimke.terrazzo.core.mobileapp.MobileAppRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

/**
 * Login state machine surface for Compose.
 *
 * Usage:
 * ```
 * val controller = rememberLoginController(onReady = { instanceId, accessToken -> … })
 * controller.start(baseUrl)   // launches Custom Tab
 * ```
 *
 * The controller:
 * 1. Builds the AppAuth authorize intent for `baseUrl`.
 * 2. Launches it via [rememberKeyedLauncher] — AppAuth renders in a Custom Tab.
 * 3. When the tab redirects to `rcha://auth-callback`, the manifest- registered
 *    `RedirectUriReceiverActivity` finishes, and the original launch delivers the response — to a
 *    recreated Activity too, if the system rotated or killed it while the tab was open.
 * 4. [LoginResultHandler] exchanges the code for tokens, writes the refresh token to [TokenVault]
 *    keyed by `instanceId` (= `baseUrl`), and hands the access token to the caller.
 */
class LoginController
internal constructor(
  private val authService: HaAuthService,
  private val launch: (baseUrl: String, intent: Intent) -> Unit,
) {

  fun start(baseUrl: String) {
    val normalized = baseUrl.trim().removeSuffix("/")
    launch(normalized, authService.prepareAuthIntent(normalized))
  }
}

internal class LoginResultHandler(
  private val authService: HaAuthService,
  private val vault: TokenVault,
  private val mobileAppRegistrar: MobileAppRegistrar,
  private val scope: CoroutineScope,
  private val onReady: (instanceId: String, accessToken: String) -> Unit,
  private val onError: (Throwable) -> Unit,
) {

  fun onResult(baseUrl: String, result: ActivityResult) {
    val data =
      result.data
        ?: run {
          onError(IllegalStateException("AppAuth returned no result data"))
          return
        }
    val authEx = AuthorizationException.fromIntent(data)
    if (authEx != null) {
      Log.e(
        TAG,
        "authorize failed: code=${authEx.code} type=${authEx.type} " +
          "desc=${authEx.errorDescription} cause=${authEx.cause}",
        authEx,
      )
      onError(authEx)
      return
    }
    val response =
      AuthorizationResponse.fromIntent(data)
        ?: run {
          onError(IllegalStateException("No authorization response in redirect"))
          return
        }
    scope.launch {
      runCatching { authService.exchangeCode(response) }
        .onSuccess { tokens ->
          val refresh = tokens.refreshToken
          val access = tokens.accessToken
          if (refresh == null || access == null) {
            onError(IllegalStateException("HA returned incomplete tokens"))
            return@onSuccess
          }
          vault.put(baseUrl, refresh)
          // mobile_app registration is best-effort: HA may have the integration
          // disabled, or be temporarily unreachable. Fire it after `onReady` so
          // a slow registration round-trip can't stall sign-in behind its
          // timeout window — the UI advances immediately and the device just
          // won't appear as a notifiable target until a later retry.
          onReady(baseUrl, access)
          runCatching { mobileAppRegistrar.register(baseUrl, access) }
            .onFailure { ex -> Log.w(TAG, "mobile_app registration failed", ex) }
        }
        .onFailure { ex ->
          val authFailure = ex as? AuthorizationException
          Log.e(
            TAG,
            "token exchange failed: code=${authFailure?.code} " +
              "type=${authFailure?.type} desc=${authFailure?.errorDescription} " +
              "cause=${ex.cause}",
            ex,
          )
          onError(ex)
        }
    }
  }

  private companion object {
    const val TAG = "LoginController"
  }
}

@Composable
fun rememberLoginController(
  onReady: (instanceId: String, accessToken: String) -> Unit,
  onError: (Throwable) -> Unit = {},
): LoginController {
  val graph = LocalTerrazzoGraph.current
  val authService = graph.authService
  val scope = rememberCoroutineScope()
  val currentOnReady by rememberUpdatedState(onReady)
  val currentOnError by rememberUpdatedState(onError)
  val results =
    remember(scope) {
      LoginResultHandler(
        authService,
        graph.tokenVault,
        graph.mobileAppRegistrar,
        scope,
        onReady = { instanceId, accessToken -> currentOnReady(instanceId, accessToken) },
        onError = { currentOnError(it) },
      )
    }
  val launch = rememberKeyedLauncher(results::onResult)
  return remember(authService, launch) { LoginController(authService, launch) }
}

/**
 * An activity-result launcher that hands [onResult] the key it was launched with.
 *
 * Sign-in leaves the app for a Custom Tab, and the system may recreate the Activity meanwhile (a
 * rotation, or process death while backgrounded). The recreated launcher delivers the pending
 * result as soon as it registers — before any effect has run — so the key lives in saved state and
 * the result is handled straight from the launcher's callback, with nothing that a later effect has
 * to wire up first.
 */
@Composable
internal fun rememberKeyedLauncher(
  onResult: (key: String, result: ActivityResult) -> Unit
): (key: String, intent: Intent) -> Unit {
  val pendingKey = rememberSaveable { mutableStateOf<String?>(null) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val key = pendingKey.value ?: return@rememberLauncherForActivityResult
      pendingKey.value = null
      onResult(key, result)
    }
  return remember(launcher) {
    { key: String, intent: Intent ->
      pendingKey.value = key
      launcher.launch(intent)
    }
  }
}
