package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Wear settings — theme picker plus a small "About" footer that shows
 * what phone the watch is paired with (and whether phone has demo mode
 * enabled). Demo mode itself is phone-driven; there's no toggle here.
 */
@Composable
fun WearSettingsScreen(
    selected: ThemeStyle,
    settings: WearSettings,
    onSelectTheme: (ThemeStyle) -> Unit,
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
        item { ListHeader { Text("Theme") } }

        item {
            Text(
                text = selected.tagline,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        items(ThemeStyle.entries) { style ->
            RadioButton(
                selected = style == selected,
                onSelect = { onSelectTheme(style) },
                label = {
                    Text(
                        text = style.displayName,
                        fontWeight = if (style == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
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
