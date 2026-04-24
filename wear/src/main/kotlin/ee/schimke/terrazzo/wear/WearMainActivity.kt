package ee.schimke.terrazzo.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.ui.WearThemePicker
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import ee.schimke.terrazzo.wear.ui.wearTypographyFor
import kotlinx.coroutines.launch

/**
 * Wear companion app — a single-screen palette browser. Picks a
 * Terrazzo [ThemeStyle], persists it via [WearPrefs], and re-themes the
 * whole watch UI live so the chosen palette can be evaluated on a
 * round face. Real HA tile rendering lands in a follow-up.
 */
class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }
}

@Composable
private fun WearApp() {
    val context = LocalContext.current
    val prefs = remember(context) { WearPrefs(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val style by prefs.themeStyle.collectAsState(initial = ThemeStyle.TerrazzoHome)

    MaterialTheme(
        colorScheme = terrazzoWearColorScheme(style),
        typography = wearTypographyFor(style),
    ) {
        AppScaffold(timeText = { TimeText() }) {
            ScreenScaffold {
                WearThemePicker(
                    selected = style,
                    onSelect = { picked -> scope.launch { prefs.setThemeStyle(picked) } },
                )
            }
        }
    }
}
