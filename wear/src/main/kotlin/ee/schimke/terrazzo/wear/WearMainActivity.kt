package ee.schimke.terrazzo.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import ee.schimke.ha.rc.LocalRelativeTimeTick
import ee.schimke.ha.rc.rememberRelativeTimeTick
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.sync.WearBenchmarkSeed
import ee.schimke.terrazzo.wear.sync.WearCardWarmup
import ee.schimke.terrazzo.wear.sync.WearLeaseController
import ee.schimke.terrazzo.wear.sync.WearSlotsController
import ee.schimke.terrazzo.wear.sync.WearSyncRepository
import ee.schimke.terrazzo.wear.ui.LocalWearCardLook
import ee.schimke.terrazzo.wear.ui.WearCardLook
import ee.schimke.terrazzo.wear.ui.WearDashboardScreen
import ee.schimke.terrazzo.wear.ui.WearDashboardsScreen
import ee.schimke.terrazzo.wear.ui.WearSectionScreen
import ee.schimke.terrazzo.wear.ui.WearSettingsScreen
import ee.schimke.terrazzo.wear.ui.WearTopLevelScreen
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import ee.schimke.terrazzo.wear.ui.wearTypographyFor
import ee.schimke.terrazzo.wear.widget.WearSlotPreviewCapturer
import ee.schimke.terrazzo.wear.widget.refreshAllSlotWidgets
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Wear companion. Top-level screen surfaces the user's pinned set (sections + cards interleaved by
 * phone-side ordering); user can drill into a pinned section, browse the full dashboard library, or
 * visit settings. Demo state is driven entirely by the phone — when phone toggles demo mode the
 * data layer publishes demo dashboards and we render the same banner + fixtures here.
 */
class WearMainActivity : ComponentActivity() {

  private lateinit var repo: WearSyncRepository
  private lateinit var slotsController: WearSlotsController

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // `:wear-benchmark` launches onto the cards a demo-mode phone would have pinned; an unpaired
    // emulator has none. Benchmark and profile variants only (BuildConfig.TEST_HOOKS).
    if (BuildConfig.TEST_HOOKS && intent?.getBooleanExtra(EXTRA_TEST_SEED_PINS, false) == true) {
      WearBenchmarkSeed.write(applicationContext)
    }
    // Have the pinned cards' templates ready (from disk, captured in the background otherwise)
    // while the first frame composes; usually already done since the phone last sent pins.
    lifecycleScope.launch { WearCardWarmup.run(applicationContext) }
    repo = WearSyncRepository(applicationContext, lifecycleScope)
    repo.start()
    // Lease tracker — heartbeats while activity is foreground, so
    // phone streams live deltas instead of batching to DataStore.
    lifecycle.addObserver(WearLeaseController(lifecycleScope, repo))
    // Slot widget plumbing (the wear-widgets capability is declared
    // statically in values-v37/wear.xml) — keeps each Slot{N}WidgetService
    // component enabled-state in lockstep with the phone-side
    // WearWidgetSlotsStore.
    slotsController = WearSlotsController(applicationContext, lifecycleScope, repo)
    slotsController.start()
    // Off-by-default preview capture exerciser. Reads
    // WearPrefs.previewCaptureEnabled; while off the controller
    // never touches the virtual-display path. Flip on locally to
    // smoke-test runtime PNG generation for the eventual picker
    // override hook.
    WearSlotPreviewCapturer(
        application = application,
        scope = lifecycleScope,
        prefs = WearPrefs(applicationContext),
        slotsFlow = repo.slots,
      )
      .start()

    // The theme and card style, read before the first composition so its first frame is the
    // pinned list in the chosen look; collected from a flow, they cost the launch an empty frame.
    // A tiny preferences file, like the phone's synchronous read of its last dashboard.
    val prefs = WearPrefs(applicationContext)
    val initialLook = runBlocking {
      WearCardLook(prefs.themeStyle.first(), prefs.cardStyle.first())
    }

    setContent { WearApp(repo, prefs, initialLook) }
  }

  override fun onDestroy() {
    super.onDestroy()
    repo.stop()
  }

  companion object {
    const val EXTRA_TEST_SEED_PINS: String = "terrazzo.test.seed_pins"
  }
}

private enum class WearScreen {
  TopLevel,
  Section,
  Dashboards,
  Dashboard,
  Settings,
}

@Composable
private fun WearApp(repo: WearSyncRepository, prefs: WearPrefs, initialLook: WearCardLook) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val selectedPalette by prefs.themeStyle.collectAsState(initial = initialLook.palette)
  val selectedCardStyle by prefs.cardStyle.collectAsState(initial = initialLook.style)
  val settings: WearSettings by repo.settings.collectAsState()
  val pinned: PinnedCardSet by repo.pinned.collectAsState()
  val sections: PinnedSectionSet by repo.sections.collectAsState()
  val dashboards: List<DashboardData> by repo.dashboards.collectAsState()
  val values by repo.values.collectAsState()

  var screen by rememberSaveable { mutableStateOf(WearScreen.TopLevel) }
  var openedDashboard by rememberSaveable { mutableStateOf<String?>(null) }
  var openedSectionKey by rememberSaveable { mutableStateOf<String?>(null) }

  // A system Back (including the watch's dismiss gesture) returns through the same hierarchy
  // as the on-screen buttons instead of closing the activity from a detail screen.
  BackHandler(enabled = screen != WearScreen.TopLevel) {
    screen = if (screen == WearScreen.Dashboard) WearScreen.Dashboards else WearScreen.TopLevel
  }

  MaterialTheme(
    colorScheme = terrazzoWearColorScheme(selectedPalette),
    typography = wearTypographyFor(selectedPalette),
  ) {
    CompositionLocalProvider(
      LocalWearCardLook provides WearCardLook(selectedPalette, selectedCardStyle),
      LocalRelativeTimeTick provides rememberRelativeTimeTick(),
    ) {
      AppScaffold(timeText = { TimeText() }) {
        when (screen) {
          WearScreen.TopLevel ->
            WearTopLevelScreen(
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
              onInputSend =
                if (
                  settings.inputEditingEnabled &&
                    !settings.demoMode &&
                    settings.baseUrl.isNotEmpty()
                )
                  repo::sendInputs
                else null,
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
                onInputSend =
                  if (
                    settings.inputEditingEnabled &&
                      !settings.demoMode &&
                      settings.baseUrl.isNotEmpty()
                  )
                    repo::sendInputs
                  else null,
                values = values,
                onBack = { screen = WearScreen.TopLevel },
                modifier = Modifier.fillMaxSize(),
              )
            }
          }
          WearScreen.Dashboards ->
            WearDashboardsScreen(
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
                onInputSend =
                  if (
                    settings.inputEditingEnabled &&
                      !settings.demoMode &&
                      settings.baseUrl.isNotEmpty()
                  )
                    repo::sendInputs
                  else null,
                values = values,
                onBack = { screen = WearScreen.Dashboards },
                modifier = Modifier.fillMaxSize(),
              )
            } else {
              // Dashboard removed mid-flight; bounce back.
              screen = WearScreen.Dashboards
            }
          }
          WearScreen.Settings ->
            WearSettingsScreen(
              selected = selectedPalette,
              settings = settings,
              onSelectTheme = { picked ->
                scope.launch {
                  prefs.setThemeStyle(picked)
                  refreshAllSlotWidgets(context.applicationContext)
                }
              },
              onBack = { screen = WearScreen.TopLevel },
              modifier = Modifier.fillMaxSize(),
              cardStyle = selectedCardStyle,
              onSelectCardStyle = { picked ->
                scope.launch {
                  prefs.setCardStyle(picked)
                  refreshAllSlotWidgets(context.applicationContext)
                }
              },
            )
        }
      }
    }
  }
}
