package ee.schimke.terrazzo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import ee.schimke.terrazzo.ui.TerrazzoTheme

/**
 * Entry point for the Terrazzo app.
 *
 * Flow (to be filled in):
 *   1. Discovery: scan LAN for `_home-assistant._tcp` instances + let
 *      the user enter a hostname.
 *   2. Auth: IndieAuth OAuth via AppAuth + Custom Tabs; refresh token
 *      saved encrypted (AndroidKeyStore AES-GCM) in DataStore.
 *   3. Dashboard picker: list `lovelace/config` dashboards.
 *   4. Dashboard view: one `RemotePreview` per card, in a Nav3
 *      responsive layout.
 *   5. Widget install: long-press card → requestPinAppWidget with
 *      live preview via the same `RemotePreview`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TerrazzoTheme {
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        // TODO(next): discovery / login / dashboard / widget-install
                        Text("Terrazzo", style = MaterialTheme.typography.headlineLarge)
                    }
                }
            }
        }
    }
}
