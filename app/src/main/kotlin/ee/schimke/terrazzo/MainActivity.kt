package ee.schimke.terrazzo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.auth.rememberLoginController
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.ui.TerrazzoTheme

/**
 * Entry point for the Terrazzo app.
 *
 * Flow:
 *   1. Discovery: scan LAN for `_home-assistant._tcp` instances + let
 *      the user enter a hostname manually.
 *   2. Auth: IndieAuth OAuth via AppAuth + Custom Tabs; refresh token
 *      saved encrypted (AndroidKeyStore AES-GCM) in DataStore.
 *   3. Dashboard picker: list `lovelace/config` dashboards. (pending)
 *   4. Dashboard view: one `RemotePreview` per card. (pending)
 *   5. Widget install: long-press card → `requestPinAppWidget` with
 *      live preview. (pending)
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TerrazzoTheme {
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        TerrazzoRoot()
                    }
                }
            }
        }
    }
}

@Composable
private fun TerrazzoRoot() {
    var instanceId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<Throwable?>(null) }

    val login = rememberLoginController(
        onReady = { id, _accessToken ->
            // TODO: open a WebSocket with `accessToken`, fetch dashboards.
            instanceId = id
        },
        onError = { lastError = it },
    )

    when (val current = instanceId) {
        null -> DiscoveryScreen(onInstancePicked = { login.start(it) })
        else -> ConnectedPlaceholder(current)
    }

    lastError?.let { err ->
        // Minimal error surface; a snackbar will replace this once the
        // scaffold grows.
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Login failed", style = MaterialTheme.typography.titleMedium)
            Text(err.message ?: err::class.simpleName.orEmpty())
        }
    }
}

@Composable
private fun ConnectedPlaceholder(baseUrl: String) {
    // Stand-in for the dashboard picker — wired in the next commit.
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Connected", style = MaterialTheme.typography.headlineSmall)
        Text(baseUrl, style = MaterialTheme.typography.bodyMedium)
        Text("Dashboard picker coming next.", style = MaterialTheme.typography.bodySmall)
    }
}
