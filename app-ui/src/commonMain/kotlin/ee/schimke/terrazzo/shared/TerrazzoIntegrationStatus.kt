package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.TerrazzoIntegration

/**
 * Settings' line on the Terrazzo integration, from the `terrazzo/info` handshake: installed,
 * missing or too old ("install or update the integration"), or newer than this app ("update the
 * app"). Nothing while the answer isn't known: not connected yet, or the handshake failed.
 */
@Composable
fun TerrazzoIntegrationStatus(integration: TerrazzoIntegration?) {
  val status = integration?.let(::terrazzoIntegrationStatus) ?: return
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("Terrazzo integration", style = MaterialTheme.typography.labelMedium)
    Text(status.text, style = MaterialTheme.typography.bodyMedium)
    if (status.offersInstall) {
      val uriHandler = LocalUriHandler.current
      TextButton(onClick = { uriHandler.openUri(TERRAZZO_MARKETPLACE_URL) }) {
        Text(if (integration is TerrazzoIntegration.Absent) "Install" else "Update")
      }
    }
  }
}

/** What Settings says about [integration], and whether to offer installing or updating it. */
data class TerrazzoIntegrationStatusText(val text: String, val offersInstall: Boolean)

fun terrazzoIntegrationStatus(integration: TerrazzoIntegration): TerrazzoIntegrationStatusText =
  when (integration) {
    is TerrazzoIntegration.Installed ->
      TerrazzoIntegrationStatusText(
        integration.info.version?.let { "Installed, version $it." } ?: "Installed.",
        offersInstall = false,
      )
    TerrazzoIntegration.Absent ->
      TerrazzoIntegrationStatusText(
        "Not installed on this Home Assistant. Install it for dashboard editing, the built-in " +
          "agent, notifications that wake this app and card designs shared across devices.",
        offersInstall = true,
      )
    is TerrazzoIntegration.TooOld ->
      TerrazzoIntegrationStatusText(
        (integration.version?.let { "Version $it" } ?: "This version") +
          " is older than this app expects. It still works, but update it for everything this " +
          "app can do.",
        offersInstall = true,
      )
    is TerrazzoIntegration.Unsupported ->
      TerrazzoIntegrationStatusText(
        "The integration on this Home Assistant is newer than this app understands. Update the " +
          "app to use it.",
        offersInstall = false,
      )
  }

/** Opens the Terrazzo integration's repository in the person's Home Assistant (HACS). */
const val TERRAZZO_MARKETPLACE_URL =
  "https://my.home-assistant.io/redirect/marketplace_repository/" +
    "?owner=yschimke&repository=homeassistant-remotecompose&category=integration"
