@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.web

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.ha.client.HaConfig
import ee.schimke.terrazzo.shared.*
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@JsFun(
  "() => /\\/demo\\/?$/.test(location.pathname) || new URLSearchParams(location.search).get('demo') === '1' ? 'demo' : 'app'"
)
private external fun entryMode(): String

@JsFun("() => location.origin + '/demo-ha'") private external fun demoBackendUrl(): String

fun main() {
  val panel = isHomeAssistantPanel()
  if (panel || entryMode() == "demo") return show(panel, signedInServer = "")
  // Outside the panel (GitHub Pages, a Wasmo app, any other origin) the page signs in itself;
  // finish a sign-in redirect, or pick up a remembered one, before the first frame.
  MainScope().launch {
    val server =
      try {
        restoreSignIn().await().toString()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Throwable) {
        ""
      }
    show(panel = false, signedInServer = server)
  }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun show(panel: Boolean, signedInServer: String) {
  val signedIn = signedInServer.isNotEmpty()
  ComposeViewport(viewportContainerId = "app", configure = { enableBrowserWindowInsets = true }) {
    if (entryMode() == "demo") PublicDemo()
    else if (panel)
      TerrazzoMultiplatformApp(
        hostedConfig = HaConfig(homeAssistantOrigin(), ""),
        hostedAccessToken = ::currentPanelAccessToken,
        initialDashboardPath = panelDashboard().ifBlank { null },
        hostedAction = ::panelAction,
        registerReturnHome = ::panelReturnHome,
      )
    else
      TerrazzoMultiplatformApp(
        hostedConfig = if (signedIn) HaConfig(signedInServer, "") else null,
        hostedAccessToken = if (signedIn) ::currentSignInAccessToken else null,
        signInWithHomeAssistant = ::signIn,
        onSignOut = ::signOut,
      )
  }
}

// Browser OAuth from ha-oauth.js, loaded before this module by index.html.
@JsFun("() => window.terrazzoOAuth.restore()")
private external fun restoreSignIn(): Promise<JsString>

@JsFun("(server) => window.terrazzoOAuth.signIn(server)")
private external fun signIn(server: String)

@JsFun("() => window.terrazzoOAuth.signOut()") private external fun signOut()

@JsFun("() => window.terrazzoOAuth.accessToken()")
private external fun signInAccessToken(): Promise<JsString>

private suspend fun currentSignInAccessToken(): String = signInAccessToken().await().toString()

// Only the locally served same-origin HA panel exposes this capability. Refresh tokens stay
// inside the HA frontend; a fresh short-lived access token is requested on every reconnect.
@JsFun("() => !!window.frameElement?.terrazzoHost")
private external fun isHomeAssistantPanel(): Boolean

@JsFun("() => window.location.origin") private external fun homeAssistantOrigin(): String

@JsFun("() => window.frameElement.terrazzoHost.getAccessToken()")
private external fun panelAccessToken(): Promise<JsString>

@JsFun("() => window.frameElement.terrazzoHost.getDashboard()")
private external fun panelDashboard(): String

@JsFun("(payload) => window.frameElement.terrazzoHost.dispatchAction(payload)")
private external fun panelAction(payload: String): Boolean

// A panel older than this app has no setReturnHome; the wall panel's idle return then does nothing.
@JsFun("(returnHome) => window.frameElement.terrazzoHost.setReturnHome?.(returnHome)")
private external fun panelReturnHome(returnHome: () -> Unit)

private suspend fun currentPanelAccessToken(): String = panelAccessToken().await().toString()

@Composable
private fun PublicDemo() {
  val source = remember { PublicDemoDataSource(demoBackendUrl()) }
  DisposableEffect(source) { onDispose { source.close() } }
  var frame by remember { mutableStateOf(DemoFrame(null, DemoDataStatus.CONNECTING)) }
  var controlError by remember { mutableStateOf<String?>(null) }
  var pending by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(source) { demoFrames(source).collect { frame = it } }
  fun control(action: suspend () -> Unit) {
    if (pending || frame.status != DemoDataStatus.LIVE) return
    controlError = null
    pending = true
    scope.launch {
      try {
        val updated =
          withTimeout(10_000) {
            action()
            source.read()
          }
        if (frame.status == DemoDataStatus.LIVE) frame = DemoFrame(updated, DemoDataStatus.LIVE)
      } catch (_: TimeoutCancellationException) {
        controlError = "Could not update demo state · try again"
      } catch (e: CancellationException) {
        throw e
      } catch (_: Throwable) {
        controlError = "Could not update demo state · try again"
      } finally {
        pending = false
      }
    }
  }
  val enabled = !pending && frame.status == DemoDataStatus.LIVE
  DashboardExperience(
    frame.data?.dashboard,
    frame.data?.snapshot,
    dataStatus = frame.status,
    controlError = controlError,
    onToggle = if (!enabled) null else { id -> control { source.toggle(id) } },
    onTemperatureChange =
      if (!enabled) null else { temperature -> control { source.setTemperature(temperature) } },
  )
}
