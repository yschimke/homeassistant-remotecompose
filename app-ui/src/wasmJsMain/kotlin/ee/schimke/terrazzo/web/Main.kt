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
    try {
      initializeBrowserAppStorage()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      showStorageLoadFailure()
      return@launch
    }
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
  ComposeViewport(viewportContainerId = "app", configure = { enableBrowserWindowInsets = true }) {
    if (entryMode() == "demo") PublicDemo() else App(panel, signedInServer)
  }
}

@Composable
private fun App(panel: Boolean, signedInServer: String) {
  val signedIn = signedInServer.isNotEmpty()
  val storageLabel = browserAppStorageLabel()
  // In the panel the app draws in Home Assistant's theme and follows it when it changes.
  var hostTheme by remember { mutableStateOf(if (panel) HostTheme.parse(panelTheme()) else null) }
  var fullScreen by remember { mutableStateOf(isFullScreen(panel)) }
  DisposableEffect(Unit) {
    if (panel) watchPanelTheme { hostTheme = HostTheme.parse(it) }
    watchFullScreen(panel) { fullScreen = it }
    onDispose {}
  }
  val fullScreenToggle: (() -> Unit)? =
    if (canFullScreen(panel)) ({ toggleFullScreen(panel) }) else null
  if (panel)
    TerrazzoMultiplatformApp(
      settingsStorageLabel = storageLabel,
      hostedConfig = HaConfig(homeAssistantOrigin(), ""),
      hostedAccessToken = ::currentPanelAccessToken,
      initialDashboardPath = panelDashboard().ifBlank { null },
      hostedAction = ::panelAction,
      registerReturnHome = ::panelReturnHome,
      hostTheme = hostTheme,
      isFullScreen = fullScreen,
      onToggleFullScreen = fullScreenToggle,
    )
  else
    TerrazzoMultiplatformApp(
      settingsStorageLabel = storageLabel,
      hostedConfig = if (signedIn) HaConfig(signedInServer, "") else null,
      hostedAccessToken = if (signedIn) ::currentSignInAccessToken else null,
      signInWithHomeAssistant = ::signIn,
      onSignOut = ::signOut,
      isFullScreen = fullScreen,
      onToggleFullScreen = fullScreenToggle,
    )
}

// A panel older than this app has no getTheme; the app then follows the browser's light or dark.
@JsFun("() => window.frameElement.terrazzoHost.getTheme?.() ?? ''")
private external fun panelTheme(): String

@JsFun("(listener) => window.frameElement.terrazzoHost.setThemeListener?.(listener)")
private external fun watchPanelTheme(listener: (String) -> Unit)

// The panel fullscreens itself, hiding HA's sidebar too; elsewhere (or in an older panel) the page.
@JsFun(
  "(panel) => panel && window.frameElement.terrazzoHost.toggleFullscreen ? !!(window.parent.document.fullscreenEnabled || window.parent.document.webkitFullscreenEnabled) : !!(document.fullscreenEnabled || document.webkitFullscreenEnabled)"
)
private external fun canFullScreen(panel: Boolean): Boolean

@JsFun(
  "(panel) => panel && window.frameElement.terrazzoHost.isFullscreen ? window.frameElement.terrazzoHost.isFullscreen() : !!(document.fullscreenElement || document.webkitFullscreenElement)"
)
private external fun isFullScreen(panel: Boolean): Boolean

@JsFun(
  "(panel) => { const host = panel ? window.frameElement.terrazzoHost : null; if (host?.toggleFullscreen) return host.toggleFullscreen(); if (document.fullscreenElement || document.webkitFullscreenElement) (document.exitFullscreen || document.webkitExitFullscreen).call(document); else Promise.resolve((document.documentElement.requestFullscreen || document.documentElement.webkitRequestFullscreen)?.call(document.documentElement)).catch(() => {}); }"
)
private external fun toggleFullScreen(panel: Boolean)

@JsFun(
  "(panel, listener) => { const host = panel ? window.frameElement.terrazzoHost : null; if (host?.setFullscreenListener) return host.setFullscreenListener(listener); for (const type of ['fullscreenchange', 'webkitfullscreenchange']) document.addEventListener(type, () => listener(!!(document.fullscreenElement || document.webkitFullscreenElement))); }"
)
private external fun watchFullScreen(panel: Boolean, listener: (Boolean) -> Unit)

@JsFun("() => window.terrazzoBackend.showStorageError()")
private external fun showStorageLoadFailure()

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
