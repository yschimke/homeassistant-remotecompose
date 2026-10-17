package ee.schimke.terrazzo.web

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.ha.model.*
import ee.schimke.terrazzo.shared.DashboardExperience
import ee.schimke.terrazzo.shared.TerrazzoMultiplatformApp
import kotlinx.serialization.json.*

@JsFun(
  "() => new URLSearchParams(location.search).get('host') === 'homeassistant' ? 'host' : /\\/demo\\/?$/.test(location.pathname) || new URLSearchParams(location.search).get('demo') === '1' ? 'demo' : 'app'"
)
private external fun entryMode(): String

@JsFun("(receive) => window.terrazzoHost.connect(receive)")
private external fun connectHost(receive: (String) -> Unit)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(viewportContainerId = "app", configure = { enableBrowserWindowInsets = true }) {
    when (entryMode()) {
      "demo" -> DashboardExperience()
      "host" -> HostDashboard()
      else -> TerrazzoMultiplatformApp()
    }
  }
}

@Composable
private fun HostDashboard() {
  var payload by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) { connectHost { payload = it } }
  val parsed =
    remember(payload) {
      payload?.let { raw ->
        runCatching {
          val json = Json { ignoreUnknownKeys = true }
          val message = json.parseToJsonElement(raw).jsonObject
          val dashboard =
            json.decodeFromJsonElement(Dashboard.serializer(), message.getValue("dashboard"))
          val states =
            message.getValue("states").jsonObject.mapValues { (id, value) ->
              val state = value.jsonObject
              EntityState(
                id,
                state.getValue("state").jsonPrimitive.content,
                state["attributes"]?.jsonObject ?: JsonObject(emptyMap()),
              )
            }
          dashboard to HaSnapshot(states)
        }
      }
    }
  val current = parsed?.getOrNull()
  if (current != null) DashboardExperience(current.first, current.second, demo = false)
  else
    Text(
      if (parsed?.isFailure == true) "Could not read this dashboard."
      else "Loading Home Assistant dashboard…"
    )
}
