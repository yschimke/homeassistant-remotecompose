package ee.schimke.ha.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Scaffold { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    DashboardScreen()
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen() {
    // TODO:
    //  1. Read HA host + token from shared prefs or an input screen.
    //  2. HaClient(config).fetchDashboard(null) + snapshot().
    //  3. For each view, call DashboardRenderer(registry).RenderView inside a
    //     RemoteComposeWriter capture, then feed the bytes to a
    //     RemoteComposePlayer composable (from remote-player-compose).
    //  4. Handle unsupported card types via a placeholder; surface the list
    //     from DashboardRenderer.unsupportedIn for visibility.
    Text("HA RemoteCompose demo — wire up HaClient + player here")
}
