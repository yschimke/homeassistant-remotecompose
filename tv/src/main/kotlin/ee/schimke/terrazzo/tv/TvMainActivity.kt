package ee.schimke.terrazzo.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.terrazzoColorScheme
import ee.schimke.ha.rc.components.terrazzoTypographyFor
import ee.schimke.terrazzo.tv.data.TvPrefs
import ee.schimke.terrazzo.tv.ui.TvKioskPreview
import ee.schimke.terrazzo.tv.ui.TvThemePicker
import kotlinx.coroutines.launch

/**
 * TV companion. Two-column kiosk layout: theme picker on the left runs
 * the DPAD focus, the right pane re-skins live as the selection
 * changes. Demo mode is a local toggle (persisted via [TvPrefs]) — when
 * on, the kiosk preview shows animated `TvDemoData` instead of the
 * static placeholder, so the room sees motion without a working HA
 * instance.
 */
class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvApp() }
    }
}

@Composable
private fun TvApp() {
    val context = LocalContext.current
    val prefs = remember(context) { TvPrefs(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val style by prefs.themeStyle.collectAsState(initial = ThemeStyle.TerrazzoKiosk)
    val demoMode by prefs.demoMode.collectAsState(initial = false)

    val colors = terrazzoColorScheme(style, darkTheme = true)
    MaterialTheme(colorScheme = colors, typography = terrazzoTypographyFor(style)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(
                modifier = Modifier.width(360.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TvThemePicker(
                    selected = style,
                    onSelect = { picked -> scope.launch { prefs.setThemeStyle(picked) } },
                )
                TvDemoToggle(
                    enabled = demoMode,
                    onChange = { v -> scope.launch { prefs.setDemoMode(v) } },
                )
            }
            TvKioskPreview(
                style = style,
                demoMode = demoMode,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TvDemoToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Demo mode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Animate the kiosk with fake data — handy for room photos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
        )
    }
}
