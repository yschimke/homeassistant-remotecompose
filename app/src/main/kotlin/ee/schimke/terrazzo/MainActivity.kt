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
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as TerrazzoApplication).graph

        // Debug-only test hatch: when the launching Intent carries
        // `terrazzo.test.demo_mode=true` we flip the persisted
        // demo-mode flag synchronously before the first composition,
        // so the auth flow short-circuits to a demo session and a
        // uiautomator test can land directly on the dashboard. Gated
        // on BuildConfig.DEBUG so production builds don't expose
        // the bypass even if the extra is present.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_TEST_DEMO_MODE, false) == true) {
            runBlocking { graph.preferencesStore.setDemoMode(true) }
        }

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

    companion object {
        const val EXTRA_TEST_DEMO_MODE: String = "terrazzo.test.demo_mode"
    }
}
