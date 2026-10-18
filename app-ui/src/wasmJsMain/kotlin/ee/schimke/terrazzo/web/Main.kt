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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@JsFun(
  "() => /\\/demo\\/?$/.test(location.pathname) || new URLSearchParams(location.search).get('demo') === '1' ? 'demo' : 'app'"
)
private external fun entryMode(): String

@JsFun("() => location.origin + '/demo-ha'") private external fun demoBackendUrl(): String

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val hosted = isHomeAssistantPanel()
  ComposeViewport(viewportContainerId = "app", configure = { enableBrowserWindowInsets = true }) {
    if (entryMode() == "demo") PublicDemo()
    else
      TerrazzoMultiplatformApp(
        hostedConfig = if (hosted) HaConfig(homeAssistantOrigin(), "") else null,
        hostedAccessToken = if (hosted) ::currentPanelAccessToken else null,
        initialDashboardPath = if (hosted) panelDashboard().ifBlank { null } else null,
        hostedAction = if (hosted) ::panelAction else null,
      )
  }
}

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
  DashboardExperience(
    frame.data?.dashboard,
    frame.data?.snapshot,
    dataStatus = frame.status,
    controlError = controlError,
    onToggle =
      if (pending || frame.status != DemoDataStatus.LIVE) null
      else
        { id ->
          controlError = null
          pending = true
          scope.launch {
            try {
              val updated =
                withTimeout(10_000) {
                  source.toggle(id)
                  source.read()
                }
              if (frame.status == DemoDataStatus.LIVE) {
                frame = DemoFrame(updated, DemoDataStatus.LIVE)
              }
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
        },
  )
}
