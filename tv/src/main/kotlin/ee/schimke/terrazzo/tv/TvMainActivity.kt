package ee.schimke.terrazzo.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
 * TV companion. Renders a two-column kiosk dashboard preview: the
 * picker on the left runs the DPAD focus, the right pane re-skins
 * itself live as the selection changes. Theme is persisted via
 * [TvPrefs] (defaults to [ThemeStyle.TerrazzoKiosk] — the wall-panel
 * brief). The full HA dashboard wires onto the right pane in a
 * follow-up.
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

    val colors = terrazzoColorScheme(style, darkTheme = true)
    MaterialTheme(colorScheme = colors, typography = terrazzoTypographyFor(style)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            TvThemePicker(
                selected = style,
                onSelect = { picked -> scope.launch { prefs.setThemeStyle(picked) } },
            )
            TvKioskPreview(
                style = style,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
