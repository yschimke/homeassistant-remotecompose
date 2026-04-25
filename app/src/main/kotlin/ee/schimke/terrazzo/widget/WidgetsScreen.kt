package ee.schimke.terrazzo.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Installed-widgets screen, reached via the dashboard top-bar's
 * overflow menu. Placeholder — wired in the next pass with:
 *   - list of installed `AppWidgetId`s + their card configs (from DataStore),
 *   - an "install new" entry that walks the user to a dashboard to long-press,
 *   - up-to-5 cap enforced here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Installed widgets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp),
        ) {
            Text(
                "Long-press a card on a dashboard to install it here. Up to 5.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
