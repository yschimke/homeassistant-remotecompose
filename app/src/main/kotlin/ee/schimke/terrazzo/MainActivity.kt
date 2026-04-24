package ee.schimke.terrazzo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.ui.toThemeStyle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as TerrazzoApplication).graph
        setContent {
            CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
                val themePref by graph.preferencesStore.themeStyle
                    .collectAsState(initial = ThemePref.TerrazzoHome)
                val darkPref by graph.preferencesStore.darkMode
                    .collectAsState(initial = DarkModePref.Follow)
                TerrazzoTheme(style = themePref.toThemeStyle(), darkMode = darkPref) {
                    TerrazzoApp()
                }
            }
        }
    }
}
