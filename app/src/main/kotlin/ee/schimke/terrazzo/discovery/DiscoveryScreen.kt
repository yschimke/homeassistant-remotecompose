package ee.schimke.terrazzo.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.catch

/**
 * First-run screen: show HA instances discovered on the LAN, let the
 * user pick one, or type a hostname manually. Selecting an instance
 * hands control to the login activity which opens a Custom Tab.
 */
@Composable
fun DiscoveryScreen(
    onInstancePicked: (baseUrl: String) -> Unit,
) {
    val context = LocalContext.current
    val discovery = remember { HaDiscovery(context) }
    val found = remember { mutableStateListOf<HaInstance>() }
    var scanning by remember { mutableStateOf(true) }
    var manualHost by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        discovery.scan()
            .catch { scanning = false }
            .collect { instance ->
                if (found.none { it.baseUrl == instance.baseUrl }) found.add(instance)
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect to Home Assistant", style = MaterialTheme.typography.headlineMedium)

        if (found.isEmpty() && scanning) {
            CircularProgressIndicator()
            Text("Scanning your network…", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(found) { instance ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInstancePicked(instance.baseUrl) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(instance.name, style = MaterialTheme.typography.titleMedium)
                        Text(instance.baseUrl, style = MaterialTheme.typography.bodySmall)
                        instance.version?.let { Text("HA $it", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Or enter manually", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = manualHost,
            onValueChange = { manualHost = it },
            label = { Text("http://homeassistant.local:8123") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { onInstancePicked(manualHost.trim().removeSuffix("/")) },
            enabled = manualHost.isNotBlank(),
        ) { Text("Connect") }
    }
}
