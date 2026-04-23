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
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter

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

/**
 * Canonical example of adding a custom-card converter on top of the
 * built-ins. Downstream apps register their own `custom:*` converters
 * the same way.
 */
private val demoRegistry: CardRegistry = defaultRegistry().withEnhancedShutter()

@Composable
private fun DashboardScreen() {
    // TODO:
    //  1. Read HA host + token from shared prefs or an input screen.
    //  2. HaClient(config).fetchDashboard(null) + snapshot().
    //  3. For each view, call DashboardRenderer(demoRegistry).RenderView
    //     inside a RemoteComposeWriter capture, then feed the bytes to a
    //     RemoteComposePlayer composable (from remote-player-compose).
    //  4. Handle unsupported card types via a placeholder; surface the list
    //     from DashboardRenderer.unsupportedIn for visibility.
    Text("HA RemoteCompose demo — registry size: ${demoRegistry.supported().size}")
}
