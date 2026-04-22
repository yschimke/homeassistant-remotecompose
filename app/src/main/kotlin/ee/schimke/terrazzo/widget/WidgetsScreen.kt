package ee.schimke.terrazzo.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Installed-widgets tab. Placeholder — wired in the next pass with:
 *   - list of installed `AppWidgetId`s + their card configs (from DataStore),
 *   - an "install new" entry that walks the user to a dashboard to long-press,
 *   - up-to-5 cap enforced here.
 */
@Composable
fun WidgetsScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Text("Widgets", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Long-press a card on a dashboard to install it here. Up to 5.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
