package ee.schimke.terrazzo.auth

import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.TokenVault
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
 *  1. Builds the AppAuth authorize intent for `baseUrl`.
 *  2. Launches it via `rememberLauncherForActivityResult` — AppAuth
 *     renders in a Custom Tab.
 *  3. When the tab redirects to `rcha://auth-callback`, the manifest-
 *     registered `RedirectUriReceiverActivity` finishes, and the
 *     original launch delivers the response.
 *  4. Exchanges the code for tokens, writes the refresh token to
 *     [TokenVault] keyed by `instanceId` (= `baseUrl`), and hands the
 *     access token to the caller.
 */
class LoginController internal constructor(
    private val authService: HaAuthService,
    private val vault: TokenVault,
    private val launcher: ActivityResultLauncher<Intent>,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val pendingBaseUrl: MutableState<String?>,
    private val onReady: (instanceId: String, accessToken: String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    fun start(baseUrl: String) {
        val normalized = baseUrl.trim().removeSuffix("/")
        pendingBaseUrl.value = normalized
        launcher.launch(authService.prepareAuthIntent(normalized))
    }

    internal fun onResult(result: ActivityResult) {
        val data = result.data ?: run {
            onError(IllegalStateException("AppAuth returned no result data"))
            return
        }
        val authEx = AuthorizationException.fromIntent(data)
        if (authEx != null) {
            Log.e(TAG, "authorize failed: code=${authEx.code} type=${authEx.type} " +
                "desc=${authEx.errorDescription} cause=${authEx.cause}", authEx)
            onError(authEx)
            return
        }
        val response = AuthorizationResponse.fromIntent(data) ?: run {
            onError(IllegalStateException("No authorization response in redirect"))
            return
        }
        val baseUrl = pendingBaseUrl.value ?: return
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
                    onReady(baseUrl, access)
                }
                .onFailure { ex ->
                    val authFailure = ex as? AuthorizationException
                    Log.e(TAG, "token exchange failed: code=${authFailure?.code} " +
                        "type=${authFailure?.type} desc=${authFailure?.errorDescription} " +
                        "cause=${ex.cause}", ex)
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
    onError: (Throwable) -> Unit = { },
): LoginController {
    val graph = LocalTerrazzoGraph.current
    val authService = graph.authService
    val vault = graph.tokenVault
    val scope = rememberCoroutineScope()
    val pendingBaseUrl = remember { mutableStateOf<String?>(null) }

    val controllerRef = remember { mutableStateOf<LoginController?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controllerRef.value?.onResult(result)
    }

    val controller = remember(launcher) {
        LoginController(authService, vault, launcher, scope, pendingBaseUrl, onReady, onError)
    }
    LaunchedEffect(controller) { controllerRef.value = controller }
    return controller
}
