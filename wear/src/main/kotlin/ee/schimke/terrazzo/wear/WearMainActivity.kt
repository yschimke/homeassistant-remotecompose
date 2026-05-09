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
import ee.schimke.terrazzo.wear.sync.WearSlotsController
import ee.schimke.terrazzo.wear.sync.WearSyncRepository
import ee.schimke.terrazzo.wear.ui.WearDashboardScreen
import ee.schimke.terrazzo.wear.ui.WearDashboardsScreen
import ee.schimke.terrazzo.wear.ui.WearSectionScreen
import ee.schimke.terrazzo.wear.ui.WearSettingsScreen
import ee.schimke.terrazzo.wear.ui.WearTopLevelScreen
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import ee.schimke.terrazzo.wear.ui.wearTypographyFor
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import kotlinx.coroutines.launch

/**
 * Wear companion. Top-level screen surfaces the user's pinned set
 * (sections + cards interleaved by phone-side ordering); user can
 * drill into a pinned section, browse the full dashboard library, or
 * visit settings. Demo state is driven entirely by the phone — when
 * phone toggles demo mode the data layer publishes demo dashboards
 * and we render the same banner + fixtures here.
 */
class WearMainActivity : ComponentActivity() {

    private lateinit var repo: WearSyncRepository
    private lateinit var slotsController: WearSlotsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WearSyncRepository(applicationContext, lifecycleScope)
        repo.start()
        // Lease tracker — heartbeats while activity is foreground, so
        // phone streams live deltas instead of batching to DataStore.
        lifecycle.addObserver(WearLeaseController(lifecycleScope, repo))
        // Slot widget plumbing — advertises the wear-widgets capability
        // when the runtime supports it, then keeps each Slot{N}WidgetService
        // component enabled-state in lockstep with the phone-side
        // WearWidgetSlotsStore.
        slotsController = WearSlotsController(applicationContext, lifecycleScope, repo)
        slotsController.start()

        setContent { WearApp(repo) }
    }

    override fun onDestroy() {
        super.onDestroy()
        repo.stop()
        slotsController.stop()
    }
}

private enum class WearScreen { TopLevel, Section, Dashboards, Dashboard, Settings }

@Composable
private fun WearApp(repo: WearSyncRepository) {
    val context = LocalContext.current
    val prefs = remember(context) { WearPrefs(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val style by prefs.themeStyle.collectAsState(initial = ThemeStyle.TerrazzoHome)
    val settings: WearSettings by repo.settings.collectAsState()
    val pinned: PinnedCardSet by repo.pinned.collectAsState()
    val sections: PinnedSectionSet by repo.sections.collectAsState()
    val dashboards: List<DashboardData> by repo.dashboards.collectAsState()
    val values by repo.values.collectAsState()

    var screen by rememberSaveable { mutableStateOf(WearScreen.TopLevel) }
    var openedDashboard by rememberSaveable { mutableStateOf<String?>(null) }
    var openedSectionKey by rememberSaveable { mutableStateOf<String?>(null) }

    MaterialTheme(
        colorScheme = terrazzoWearColorScheme(style),
        typography = wearTypographyFor(style),
    ) {
        AppScaffold(timeText = { TimeText() }) {
            ScreenScaffold {
                when (screen) {
                    WearScreen.TopLevel -> WearTopLevelScreen(
                        settings = settings,
                        pinned = pinned,
                        sections = sections,
                        values = values,
                        onOpenSection = { section ->
                            openedSectionKey = section.sectionKey
                            screen = WearScreen.Section
                        },
                        onBrowseDashboards = { screen = WearScreen.Dashboards },
                        onOpenSettings = { screen = WearScreen.Settings },
                        modifier = Modifier.fillMaxSize(),
                    )
                    WearScreen.Section -> {
                        val key = openedSectionKey
                        val resolved = sections.sections.firstOrNull { it.sectionKey == key }
                        if (resolved == null && key != null) {
                            // Section was unpinned mid-flight; bounce back so the
                            // user lands somewhere meaningful instead of an empty
                            // detail screen.
                            screen = WearScreen.TopLevel
                        } else {
                            WearSectionScreen(
                                section = resolved,
                                values = values,
                                onBack = { screen = WearScreen.TopLevel },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    WearScreen.Dashboards -> WearDashboardsScreen(
                        dashboards = dashboards,
                        onDashboardPicked = {
                            openedDashboard = it.urlPath
                            screen = WearScreen.Dashboard
                        },
                        onBack = { screen = WearScreen.TopLevel },
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
                        onBack = { screen = WearScreen.TopLevel },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
