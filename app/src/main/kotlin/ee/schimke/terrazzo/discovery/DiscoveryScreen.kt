package ee.schimke.terrazzo.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * First-run screen: ask for the HA base URL.
 *
 * Default-filled with the emulator-loopback address of the integration
 * Docker HA (`http://10.0.2.2:8124`) so the conference-demo dev loop
 * is one tap:
 *   `integration/ && docker compose up -d homeassistant`, then launch
 *   the app, tap Connect. A real (on-LAN) hostname can be typed in.
 *
 * A previous version scanned `_home-assistant._tcp` via mDNS; removed
 * by request — the app only needs to talk to the one known instance.
 * `HaDiscovery` stays in the module for future reuse.
 */
@Composable
fun DiscoveryScreen(
    onInstancePicked: (baseUrl: String) -> Unit,
    onDemoSelected: () -> Unit = {},
) {
    var host by rememberSaveable { mutableStateOf(DEMO_HOST) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect to Home Assistant", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Defaulted to the integration Docker HA on the emulator-loopback " +
                "address. Edit to point at your own instance if needed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { onInstancePicked(host.trim().removeSuffix("/")) },
            enabled = host.isNotBlank(),
        ) { Text("Connect") }
        TextButton(onClick = onDemoSelected) {
            Text("Try demo mode (no login)")
        }
    }
}

/** Emulator loopback → host machine's port 8124, where `integration/` HA runs. */
private const val DEMO_HOST = "http://10.0.2.2:8124"
