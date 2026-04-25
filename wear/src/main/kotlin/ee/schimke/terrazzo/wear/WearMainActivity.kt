package ee.schimke.terrazzo.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.sync.WearLeaseController
import ee.schimke.terrazzo.wear.sync.WearSyncRepository
import ee.schimke.terrazzo.wear.ui.WearDashboardScreen
import ee.schimke.terrazzo.wear.ui.WearDashboardsScreen
import ee.schimke.terrazzo.wear.ui.WearHomeScreen
import ee.schimke.terrazzo.wear.ui.WearSettingsScreen
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import ee.schimke.terrazzo.wear.ui.wearTypographyFor
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import kotlinx.coroutines.launch

/**
 * Wear companion. Default screen surfaces phone's pinned cards; user can
 * browse to dashboards or visit settings (theme picker). Demo state is
 * driven entirely by the phone — when phone toggles demo mode the data
 * layer publishes demo dashboards and we render the same banner +
 * fixtures here.
 */
class WearMainActivity : ComponentActivity() {

    private lateinit var repo: WearSyncRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WearSyncRepository(applicationContext, lifecycleScope)
        repo.start()
        // Lease tracker — heartbeats while activity is foreground, so
        // phone streams live deltas instead of batching to DataStore.
        lifecycle.addObserver(WearLeaseController(lifecycleScope, repo))

        setContent { WearApp(repo) }
    }

    override fun onDestroy() {
        super.onDestroy()
        repo.stop()
    }
}

private enum class WearScreen { Home, Dashboards, Dashboard, Settings }

@Composable
private fun WearApp(repo: WearSyncRepository) {
    val context = LocalContext.current
    val prefs = remember(context) { WearPrefs(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val style by prefs.themeStyle.collectAsState(initial = ThemeStyle.TerrazzoHome)
    val settings: WearSettings by repo.settings.collectAsState()
    val pinned: PinnedCardSet by repo.pinned.collectAsState()
    val dashboards: List<DashboardData> by repo.dashboards.collectAsState()
    val values by repo.values.collectAsState()

    var screen by rememberSaveable { mutableStateOf(WearScreen.Home) }
    var openedDashboard by rememberSaveable { mutableStateOf<String?>(null) }

    MaterialTheme(
        colorScheme = terrazzoWearColorScheme(style),
        typography = wearTypographyFor(style),
    ) {
        AppScaffold(timeText = { TimeText() }) {
            ScreenScaffold {
                when (screen) {
                    WearScreen.Home -> WearHomeScreen(
                        settings = settings,
                        pinned = pinned,
                        values = values,
                        onBrowseDashboards = { screen = WearScreen.Dashboards },
                        onOpenSettings = { screen = WearScreen.Settings },
                        modifier = Modifier.fillMaxSize(),
                    )
                    WearScreen.Dashboards -> WearDashboardsScreen(
                        dashboards = dashboards,
                        onDashboardPicked = {
                            openedDashboard = it.urlPath
                            screen = WearScreen.Dashboard
                        },
                        onBack = { screen = WearScreen.Home },
                        modifier = Modifier.fillMaxSize(),
                    )
                    WearScreen.Dashboard -> {
                        val urlPath = openedDashboard
                        val board = dashboards.firstOrNull { it.urlPath == urlPath }
                        if (board != null) {
                            WearDashboardScreen(
                                dashboard = board,
                                values = values,
                                onBack = { screen = WearScreen.Dashboards },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // Dashboard removed mid-flight; bounce back.
                            screen = WearScreen.Dashboards
                        }
                    }
                    WearScreen.Settings -> WearSettingsScreen(
                        selected = style,
                        settings = settings,
                        onSelectTheme = { picked -> scope.launch { prefs.setThemeStyle(picked) } },
                        onBack = { screen = WearScreen.Home },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
