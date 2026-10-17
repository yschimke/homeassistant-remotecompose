package ee.schimke.terrazzo.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.ha.client.HaConfig
import ee.schimke.terrazzo.shared.TerrazzoMultiplatformApp
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val hosted = isHomeAssistantPanel()
  ComposeViewport(viewportContainerId = "app", configure = { enableBrowserWindowInsets = true }) {
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
