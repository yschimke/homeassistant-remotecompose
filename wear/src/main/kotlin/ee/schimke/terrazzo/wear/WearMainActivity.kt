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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.sync.WearLeaseController
import ee.schimke.terrazzo.wear.sync.WearSyncRepository
import ee.schimke.terrazzo.wear.ui.WearAboutScreen
import ee.schimke.terrazzo.wear.ui.WearDashboardScreen
import ee.schimke.terrazzo.wear.ui.WearDashboardsScreen
import ee.schimke.terrazzo.wear.ui.WearHomeScreen
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import ee.schimke.terrazzo.wear.ui.wearTypographyFor
import ee.schimke.terrazzo.wearsync.proto.CardDoc
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Wear companion. Default screen surfaces phone's pinned cards rendered
 * via [androidx.compose.remote.player.compose.RemoteDocumentPlayer]
 * — no separate UI components, no local card converters; the watch
 * just plays whatever `.rc` bytes phone publishes per card.
 *
 * Theme is **phone-driven**: the watch reads `WearSettings.themeStyle`
 * from the data layer and projects it onto its Wear Material 3 colour
 * scheme (always dark). No theme picker on the watch.
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

private enum class WearScreen { Home, Dashboards, Dashboard, About }

@Composable
private fun WearApp(repo: WearSyncRepository) {
    val settings: WearSettings by repo.settings.collectAsState()
    val pinned: PinnedCardSet by repo.pinned.collectAsState()
    val dashboards: List<DashboardData> by repo.dashboards.collectAsState()
    val cardDocs: Map<String, CardDoc> by repo.cardDocs.collectAsState()

    var screen by rememberSaveable { mutableStateOf(WearScreen.Home) }
    var openedDashboard by rememberSaveable { mutableStateOf<String?>(null) }

    // Phone publishes a `ThemePref.name`. If wear hasn't received
    // anything yet (cold start, no phone reachable), fall back to the
    // Home palette so chrome still has something to draw.
    val style = remember(settings.themeStyle) {
        runCatching { ThemeStyle.valueOf(settings.themeStyle) }.getOrNull()
            ?: ThemeStyle.TerrazzoHome
    }

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
                        cardDocs = cardDocs,
                        onBrowseDashboards = { screen = WearScreen.Dashboards },
                        onOpenAbout = { screen = WearScreen.About },
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
                                cardDocs = cardDocs,
                                onBack = { screen = WearScreen.Dashboards },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // Dashboard removed mid-flight; bounce back.
                            screen = WearScreen.Dashboards
                        }
                    }
                    WearScreen.About -> WearAboutScreen(
                        settings = settings,
                        onBack = { screen = WearScreen.Home },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
