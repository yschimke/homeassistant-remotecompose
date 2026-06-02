package ee.schimke.terrazzo.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
 * First-run screen: discover Home Assistant instances on the LAN over mDNS, with a manual URL entry
 * as a fallback.
 *
 * Behaviour:
 * * On entry, requests the `ACCESS_LOCAL_NETWORK` runtime permission (Android 16+ only —
 *   pre-Baklava devices inherit network access from the install-time permissions). The mDNS scanner
 *   starts as soon as the permission resolves (or immediately on older devices).
 * * Discovered instances appear as cards above the URL field. Tapping a card runs the same
 *   `onInstancePicked` callback the manual flow would call, so login proceeds against the resolved
 *   `base_url` (HA's zeroconf TXT) or the resolved `host:port` if that record is absent.
 * * The URL field stays available — defaulted to `http://homeassistant:8123`, the same hostname
 *   HAOS / supervisor publish on the LAN and the HA companion app uses — for users on networks
 *   where mDNS doesn't propagate (some enterprise APs, mesh setups with multicast disabled).
 *
 * The mDNS scan is cold: scrolling away from the screen / login completing cancels the discovery
 * via the underlying `NsdManager.stopServiceDiscovery` call in [HaDiscovery.scan].
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
  // the system dialog before any LAN traffic — both the mDNS scan and
  // the post-OAuth API calls need it. The pending base URL crosses the
  // dialog so the launcher's result handler can forward it to login.
  var pendingBaseUrl by remember { mutableStateOf<String?>(null) }
  var localNetworkGranted by remember {
    mutableStateOf(!needsLocalNetworkRuntime() || hasLocalNetwork(context))
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) localNetworkGranted = true
      pendingBaseUrl?.let { onInstancePicked(it) }
      pendingBaseUrl = null
    }

  // Ask for the permission on first composition rather than waiting
  // for Connect / mDNS-tap — the scan can't start without it on
  // Android 16+ and we want results before the user reaches the URL
  // field.
  LaunchedEffect(Unit) {
    if (needsLocalNetworkRuntime() && !hasLocalNetwork(context)) {
      permissionLauncher.launch(ACCESS_LOCAL_NETWORK)
    }
  }

  val instances = rememberDiscoveredInstances(enabled = localNetworkGranted)

  Scaffold(snackbarHost = snackbarHost) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().imePadding().padding(innerPadding).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Connect to Home Assistant", style = MaterialTheme.typography.headlineMedium)

      if (instances.isNotEmpty()) {
        Text(
          "Found on this network — tap to connect:",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        instances.forEach { instance ->
          DiscoveredInstanceCard(
            instance = instance,
            onClick = { onInstancePicked(instance.baseUrl) },
          )
        }
        HorizontalDivider()
        Text(
          "Or enter a URL manually:",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          "Looking for Home Assistant on this network. " +
            "If nothing shows up, enter the URL manually.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

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
      ) {
        Text("Connect")
      }
      TextButton(onClick = onDemoSelected) { Text("Try demo mode (no login)") }
    }
  }
}

@Composable
private fun DiscoveredInstanceCard(instance: HaInstance, onClick: () -> Unit) {
  OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(instance.name, style = MaterialTheme.typography.titleMedium)
      Text(
        instance.baseUrl,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      instance.version?.let {
        Text(
          "HA $it",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * Subscribes to [HaDiscovery.scan] while [enabled] is true and deduplicates by `baseUrl` (a single
 * HA may advertise multiple times — once per network interface, once per cluster member). Returns a
 * sorted list so the rendered order is stable across recompositions.
 */
@Composable
private fun rememberDiscoveredInstances(enabled: Boolean): List<HaInstance> {
  val context = LocalContext.current
  val discovery = remember(context) { HaDiscovery(context.applicationContext) }
  val byBaseUrl = remember { mutableStateMapOf<String, HaInstance>() }

  LaunchedEffect(enabled) {
    if (!enabled) return@LaunchedEffect
    discovery.scan().collect { instance -> byBaseUrl[instance.baseUrl] = instance }
  }
  return byBaseUrl.values.sortedBy { it.name.lowercase() }
}

private fun needsLocalNetworkRuntime(): Boolean =
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

private fun hasLocalNetwork(context: Context): Boolean =
  ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) ==
    PackageManager.PERMISSION_GRANTED

private fun needsLocalNetworkPermission(context: Context, baseUrl: String): Boolean {
  if (!needsLocalNetworkRuntime()) return false
  val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return false
  if (!LanConnectionPolicy.isLanHost(host)) return false
  return !hasLocalNetwork(context)
}

// String literal so we can reference it on devices below API 36 without
// triggering the lint check on Manifest.permission.ACCESS_LOCAL_NETWORK.
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Standard HAOS / supervisor LAN hostname; matches the official HA companion app default. */
private const val DEFAULT_HOST = "http://homeassistant:8123"
