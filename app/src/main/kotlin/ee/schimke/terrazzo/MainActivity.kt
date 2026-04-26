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

        // Read the auto-launch dashboard pref synchronously so the
        // first composition can land directly on the right dashboard
        // (no picker flash). DataStore reads from a tiny preferences
        // file; the runBlocking cost is < 5 ms in practice — same
        // pattern as the test hatch above.
        val initialDashboard = runBlocking { graph.preferencesStore.lastViewedDashboardNow() }

        // Offline-first cold start: if a previous session left an
        // instance pointer on disk, build a cache-only session so the
        // first composition paints immediately from cached dashboards
        // and snapshots — no spinner, no network roundtrip. The login
        // flow upgrades this to a live session as soon as the access
        // token is minted (see TerrazzoApp.LaunchedEffect that calls
        // sessionFactory.create with a fresh access token).
        val initialInstance = graph.offlineCache.lastInstance()
        val initialSession = initialInstance?.let { graph.sessionFactory.createCachedOnly(it) }

        setContent {
            CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
                val themePref by graph.preferencesStore.themeStyle
                    .collectAsState(initial = ThemePref.TerrazzoHome)
                val darkPref by graph.preferencesStore.darkMode
                    .collectAsState(initial = DarkModePref.Follow)
                TerrazzoTheme(style = themePref.toThemeStyle(), darkMode = darkPref) {
                    TerrazzoApp(
                        initialDashboard = initialDashboard,
                        initialSession = initialSession,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_TEST_DEMO_MODE: String = "terrazzo.test.demo_mode"
    }
}
