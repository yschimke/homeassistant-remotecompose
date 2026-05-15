package ee.schimke.terrazzo.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
 * Default-filled with `http://homeassistant:8123` — the hostname HAOS /
 * supervisor publish on the LAN, and what the official companion app
 * defaults to. A real (on-LAN) hostname or IP can be typed in.
 *
 * A previous version scanned `_home-assistant._tcp` via mDNS; removed
 * by request — the app only needs to talk to the one known instance.
 * `HaDiscovery` stays in the module for future reuse.
 */
@Composable
fun DiscoveryScreen(
    onInstancePicked: (baseUrl: String) -> Unit,
    onDemoSelected: () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
) {
    var host by rememberSaveable { mutableStateOf(DEFAULT_HOST) }

    Scaffold(snackbarHost = snackbarHost) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect to Home Assistant", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Defaulted to the standard Home Assistant LAN hostname. " +
                    "Edit to point at your own instance if needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

/** Standard HAOS / supervisor LAN hostname; matches the official HA companion app default. */
private const val DEFAULT_HOST = "http://homeassistant:8123"
