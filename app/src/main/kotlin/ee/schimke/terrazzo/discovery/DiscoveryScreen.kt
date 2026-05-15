package ee.schimke.terrazzo.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import java.net.URI

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
    val context = LocalContext.current

    // ACCESS_LOCAL_NETWORK is a runtime permission on Android 16+. Surface
    // the system dialog before login when the entered URL points at a LAN
    // host so the post-OAuth API calls aren't blocked. The pending URL
    // crosses the dialog so the launcher's result handler can forward it.
    var pendingBaseUrl by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingBaseUrl?.let(onInstancePicked)
        pendingBaseUrl = null
    }

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
                onClick = {
                    val trimmed = host.trim().removeSuffix("/")
                    if (needsLocalNetworkPermission(context, trimmed)) {
                        pendingBaseUrl = trimmed
                        permissionLauncher.launch(ACCESS_LOCAL_NETWORK)
                    } else {
                        onInstancePicked(trimmed)
                    }
                },
                enabled = host.isNotBlank(),
            ) { Text("Connect") }
            TextButton(onClick = onDemoSelected) {
                Text("Try demo mode (no login)")
            }
        }
    }
}

private fun needsLocalNetworkPermission(context: Context, baseUrl: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
    val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return false
    if (!LanConnectionPolicy.isLanHost(host)) return false
    return ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) !=
        PackageManager.PERMISSION_GRANTED
}

// String literal so we can reference it on devices below API 36 without
// triggering the lint check on Manifest.permission.ACCESS_LOCAL_NETWORK.
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Standard HAOS / supervisor LAN hostname; matches the official HA companion app default. */
private const val DEFAULT_HOST = "http://homeassistant:8123"
