package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Replaces the old wear-side theme picker. Theme is phone-driven — the
 * watch reads `WearSettings.themeStyle` from the data layer and there's
 * nothing to pick here. This screen is just status: which palette
 * phone is asking for, and whether phone is in demo mode.
 */
@Composable
fun WearAboutScreen(
    settings: WearSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        item { ListHeader { Text("About") } }

        item {
            Text(
                text = "Theme is set on the phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            Text(
                text = "Palette: ${settings.themeStyle.ifEmpty { "default" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        item { ListHeader { Text("Phone") } }
        item {
            Text(
                text = if (settings.demoMode) {
                    "Demo mode (offline)"
                } else if (settings.baseUrl.isNotEmpty()) {
                    settings.baseUrl
                } else {
                    "Not connected"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("Back")
            }
        }
    }
}
