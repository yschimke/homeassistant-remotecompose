package ee.schimke.terrazzo.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import ee.schimke.terrazzo.wear.sync.WearSyncRepository
import ee.schimke.terrazzo.wear.sync.WearWidgetsController
import ee.schimke.terrazzo.wear.sync.wearTapsEnabled
import ee.schimke.terrazzo.wear.ui.LocalWearCardLook
import ee.schimke.terrazzo.wear.ui.LocalWearTapSender
import ee.schimke.terrazzo.wear.ui.WearCardDetailsScreen
import ee.schimke.terrazzo.wear.ui.WearCardLook
import ee.schimke.terrazzo.wear.ui.WearCardScreen
import ee.schimke.terrazzo.wear.ui.WearDashboardScreen
import ee.schimke.terrazzo.wear.ui.WearDashboardsScreen
import ee.schimke.terrazzo.wear.ui.WearSectionScreen
import ee.schimke.terrazzo.wear.ui.WearSettingsScreen
import ee.schimke.terrazzo.wear.ui.WearTopLevelScreen
import ee.schimke.terrazzo.wear.ui.WearWidgetsScreen
import ee.schimke.terrazzo.wear.ui.fullScreenCardKey
import ee.schimke.terrazzo.wear.ui.fullScreenDeck
import ee.schimke.terrazzo.wear.ui.opensFullScreen
import ee.schimke.terrazzo.wear.ui.rememberWearColorScheme
import ee.schimke.terrazzo.wear.ui.rememberWearValues
import ee.schimke.terrazzo.wear.ui.wearCardLook
import ee.schimke.terrazzo.wear.ui.wearTypographyFor
import ee.schimke.terrazzo.wear.widget.WearWidgetPreviewCapturer
import ee.schimke.terrazzo.wear.widget.WearWidgetTaps
import ee.schimke.terrazzo.wear.widget.widgetCardOptions
import ee.schimke.terrazzo.wear.widget.widgetCardSection
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Wear companion. Top-level screen surfaces the user's pinned set (sections + cards interleaved by
 * phone-side ordering); user can drill into a pinned section, browse the full dashboard library, or
 * visit settings. Demo state is driven entirely by the phone — when phone toggles demo mode the
 * data layer publishes demo dashboards and we render the same banner + fixtures here.
 */
class WearMainActivity : ComponentActivity() {

  private lateinit var repo: WearSyncRepository
  private lateinit var widgetsController: WearWidgetsController

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
    // Widget plumbing: offers the two widget providers while the phone shows any card on the
    // watch, and redraws the placed widgets when their cards change. Each widget's card is
    // chosen on the watch.
    widgetsController = WearWidgetsController(applicationContext, lifecycleScope, repo)
    widgetsController.start()
    // Off-by-default preview capture exerciser. Reads
    // WearPrefs.previewCaptureEnabled; while off the controller
    // never touches the virtual-display path. Flip on locally to
    // smoke-test runtime PNG generation for the eventual picker
    // override hook.
    WearWidgetPreviewCapturer(
        application = application,
        scope = lifecycleScope,
        prefs = WearPrefs(applicationContext),
        pinnedFlow = repo.pinned,
      )
      .start()

    // Once per launch: a recreated activity keeps the screen it was on.
    if (savedInstanceState == null) openWidgetCard.value = widgetCardKeyOf(intent)
    // The phone's Home Assistant notifications post here (Wear OS 4+ asks first). Asked once per
    // launch until answered; the system stops asking after a refusal.
    if (
      savedInstanceState == null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }
    setContent { WearApp(repo, openWidgetCard) }
  }

  /** A widget tapped while the app runs ([WearWidgetTaps.openCardIntent]): move to its card. */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    widgetCardKeyOf(intent)?.let { openWidgetCard.value = it }
  }

  /** The card of the widget whose tap opened the app, until [WearApp] has moved to it. */
  private val openWidgetCard = MutableStateFlow<String?>(null)

  private fun widgetCardKeyOf(intent: Intent?): String? =
    intent
      ?.takeIf { it.action == ACTION_OPEN_WIDGET_CARD }
      ?.getStringExtra(EXTRA_WIDGET_CARD_KEY)
      // An empty key names no card.
      ?.ifEmpty { null }

  override fun onDestroy() {
    super.onDestroy()
    repo.stop()
  }

  companion object {
    const val EXTRA_TEST_SEED_PINS: String = "terrazzo.test.seed_pins"

    /** A Wear widget's tap: open the app on the widget's card. */
    const val ACTION_OPEN_WIDGET_CARD: String = "ee.schimke.terrazzo.wear.OPEN_WIDGET_CARD"

    /** The widget's card, by its `WidgetCardOption.cardKey`. */
    const val EXTRA_WIDGET_CARD_KEY: String = "terrazzo.widget.card_key"
  }
}

private enum class WearScreen {
  TopLevel,
  Section,
  Dashboards,
  Dashboard,
  Settings,
  Widgets,
  Card,
  CardDetails,
}

@Composable
private fun WearApp(
  repo: WearSyncRepository,
  openWidgetCard: MutableStateFlow<String?>,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings: WearSettings by repo.settings.collectAsState()
  val designRevision by repo.designRevision.collectAsState()
  val selectedPalette = settings.wearCardLook().palette
  val selectedCardStyle = settings.wearCardLook().style
  val pinned: PinnedCardSet by repo.pinned.collectAsState()
  val sections: PinnedSectionSet by repo.sections.collectAsState()
  val widgetCardsRevision by repo.widgetCardsRevision.collectAsState()
  val dashboards: List<DashboardData> by repo.dashboards.collectAsState()
  // One object for the app's life: cards read their own entities from it, so a value update
  // recomposes only the cards that show it.
  val values = rememberWearValues(repo.values)

  var screen by rememberSaveable { mutableStateOf(WearScreen.TopLevel) }
  var openedDashboard by rememberSaveable { mutableStateOf<String?>(null) }
  var openedSectionKey by rememberSaveable { mutableStateOf<String?>(null) }
  // The full-screen card: its deck (a pinned section's key, or null for the pinned list) and the
  // card it opened on (fullScreenCardKey).
  var cardDeck by rememberSaveable { mutableStateOf<String?>(null) }
  var openedCard by rememberSaveable { mutableStateOf<String?>(null) }
  fun openCard(sectionKey: String?, card: CardSummary) {
    cardDeck = sectionKey
    openedCard = fullScreenCardKey(card)
    screen = WearScreen.Card
  }

  // A Wear widget's tap: its card full screen, paging through its pinned section or the pinned
  // list; its section, or the pinned list, for a card that doesn't open full screen.
  val widgetCard by openWidgetCard.collectAsState()
  LaunchedEffect(widgetCard) {
    val key = widgetCard ?: return@LaunchedEffect
    val section = widgetCardSection(key, sections)
    val card = widgetCardOptions(pinned, sections).firstOrNull { it.cardKey == key }?.card
    if (card != null && opensFullScreen(card)) {
      // Back from the card returns through its section, as if it had been opened from there.
      section?.let { openedSectionKey = it.sectionKey }
      openCard(section?.sectionKey, card)
    } else if (section != null) {
      openedSectionKey = section.sectionKey
      screen = WearScreen.Section
    } else {
      screen = WearScreen.TopLevel
    }
    openWidgetCard.value = null
  }

  // A system Back (including the watch's dismiss gesture) returns through the same hierarchy
  // as the on-screen buttons instead of closing the activity from a detail screen.
  BackHandler(enabled = screen != WearScreen.TopLevel) {
    screen =
      when (screen) {
        WearScreen.Dashboard -> WearScreen.Dashboards
        WearScreen.Widgets -> WearScreen.Settings
        WearScreen.Card -> if (cardDeck != null) WearScreen.Section else WearScreen.TopLevel
        WearScreen.CardDetails -> WearScreen.Card
        else -> WearScreen.TopLevel
      }
  }

  MaterialTheme(
    colorScheme = rememberWearColorScheme(selectedPalette),
    typography = wearTypographyFor(selectedPalette),
  ) {
    CompositionLocalProvider(
      ee.schimke.terrazzo.wear.ui.LocalWearDesignRevision provides designRevision,
      LocalWearCardLook provides WearCardLook(selectedPalette, selectedCardStyle),
      LocalRelativeTimeTick provides rememberRelativeTimeTick(),
      // A card's taps act through the phone while it allows input editing, outside demo mode.
      LocalWearTapSender provides if (wearTapsEnabled(settings)) repo::sendTap else null,
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
              onOpenCard = { openCard(null, it.card) },
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
                onOpenCard = { card -> openCard(resolved?.sectionKey, card) },
              )
            }
          }
          WearScreen.Card -> {
            val deck = fullScreenDeck(cardDeck, pinned, sections)
            val page = deck.indexOfFirst { fullScreenCardKey(it) == openedCard }
            if (page < 0) {
              // The card left its deck mid-flight (unpinned, or its config changed): back to the
              // list it was opened from.
              screen = if (cardDeck != null) WearScreen.Section else WearScreen.TopLevel
            } else {
              WearCardScreen(
                cards = deck,
                initialPage = page,
                values = values,
                modifier = Modifier.fillMaxSize(),
                onOpenDetails = { card ->
                  // Back from the details returns to this card's page.
                  openedCard = fullScreenCardKey(card)
                  screen = WearScreen.CardDetails
                },
              )
            }
          }
          WearScreen.CardDetails -> {
            val card =
              fullScreenDeck(cardDeck, pinned, sections).firstOrNull {
                fullScreenCardKey(it) == openedCard
              }
            if (card == null) {
              screen = WearScreen.Card
            } else {
              WearCardDetailsScreen(
                summary = card,
                values = values,
                loadHistory = repo::history,
                loadStates = repo::liveStates,
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
              settings = settings,
              onBack = { screen = WearScreen.TopLevel },
              modifier = Modifier.fillMaxSize(),
              // Glance Wear widgets render from API 37 only.
              onOpenWidgets =
                if (WearWidgetsController.supportsWearWidgets()) {
                  { screen = WearScreen.Widgets }
                } else null,
            )
          WearScreen.Widgets ->
            WearWidgetsScreen(
              pinned = pinned,
              sections = sections,
              widgetCardsRevision = widgetCardsRevision,
              onBack = { screen = WearScreen.Settings },
              modifier = Modifier.fillMaxSize(),
            )
        }
      }
    }
  }
}
