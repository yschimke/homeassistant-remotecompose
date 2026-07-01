package ee.schimke.terrazzo.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.SettingsScreen
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.auth.TokenVault
import ee.schimke.terrazzo.core.cache.OfflineCache
import ee.schimke.terrazzo.core.di.HaSessionFactory
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.logs.LogConnectionStatus
import ee.schimke.terrazzo.core.logs.LogEntry
import ee.schimke.terrazzo.core.mobileapp.MobileAppRegistrar
import ee.schimke.terrazzo.core.mobileapp.MobileAppStore
import ee.schimke.terrazzo.core.monitor.CardMonitor
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.MobilePinnedSection
import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.pin.PinnedCardData
import ee.schimke.terrazzo.core.pin.SlotSize
import ee.schimke.terrazzo.core.pin.WearWidgetSlot
import ee.schimke.terrazzo.core.pin.WearWidgetSlotsStore
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.wearsync.NoOpWearSyncManager
import ee.schimke.terrazzo.core.wearsync.WearSyncManager
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.dashboard.CardHistoryScreen
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardSelectionScreen
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.dashboard.ManagePinnedContent
import ee.schimke.terrazzo.dashboard.PinRowItem
import ee.schimke.terrazzo.dashboard.historyEntityIds
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.logs.LogsContent
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.wearsync.SyncDiagnosticsContent
import ee.schimke.terrazzo.wearsync.WearWidgetsContent
import ee.schimke.terrazzo.wearsync.proto.SyncStats
import ee.schimke.terrazzo.widget.WidgetsScreen
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phone-sized device previews, one per top-level screen, plus a set of Play-Store-targeted previews
 * used by the listing graphics pipeline.
 *
 * Rendered by `:app:renderAllPreviews` so regressions (broken layouts, missing theme tokens, a
 * refactor that orphans a screen) show up in CI's artifact bundle even before the emulator job
 * runs.
 *
 * Every preview is wrapped in [TerrazzoTheme] so a [ThemeStyle] is exercised. Screens that read
 * [LocalTerrazzoGraph] (DashboardViewScreen touches `offlineCache` and `preferencesStore`) get a
 * minimal preview graph wired up below — the unused bindings throw on access.
 *
 * Default phone dimensions match Pixel 6 portrait so a full screen is visible without scrolling.
 * The Play-Store-targeted previews override those via `@Preview(device = ...)` to land on Pixel 2 /
 * 7-inch / 10-inch tablet specs instead, with `dpi` chosen to keep the rendered PNG under the
 * 1800-pixel agent capture limit while preserving the device's aspect ratio.
 */
private const val PHONE_WIDTH_DP = 412
private const val PHONE_HEIGHT_DP = 892
private const val PHONE_LANDSCAPE_MEDIUM_WIDTH_DP = 732
private const val PHONE_LANDSCAPE_MEDIUM_HEIGHT_DP = 412

// Play-Store device specs. dpi is tuned per-device so the rendered PNG
// stays under 1800 px in either dimension while preserving the real
// device aspect ratio.
//   Pixel 2     1080×1920 native (9:16)  — 411dp×731dp @ 320dpi → 822×1462
//   7-inch tab   600dp×960dp     (5:8)   — 600dp×960dp @ 240dpi → 900×1440
//   10-inch tab 800dp×1280dp     (5:8)   — 800dp×1280dp @ 224dpi → 1120×1792
private const val PIXEL_2_DEVICE = "spec:width=411dp,height=731dp,dpi=320"
private const val TABLET_7_DEVICE = "spec:width=600dp,height=960dp,dpi=240"
private const val TABLET_10_DEVICE = "spec:width=800dp,height=1280dp,dpi=224"

// Pin the demo-session clock so the snapshot's sine-wave sensor values
// (temperatures, humidity, power, lamp toggles, battery drain) render
// identically on every run. Defaulting to `System::currentTimeMillis`
// would re-seed the demo data each render and produce false preview
// diffs on every PR.
private const val DEMO_CLOCK_MS = 0L

private fun demoSession() = DemoHaSession(clock = { DEMO_CLOCK_MS })

/**
 * Minimal [TerrazzoGraph] for previews. Provides real instances of [OfflineCache] and
 * [PreferencesStore] (both are pure context-backed file/DataStore wrappers, so they cost nothing in
 * a Robolectric harness) and throws on the rest — none of the previewed screens read those
 * bindings.
 */
@Composable
private fun rememberPreviewGraph(): TerrazzoGraph {
  val context = LocalContext.current.applicationContext
  return remember(context) {
    object : TerrazzoGraph {
      override val offlineCache: OfflineCache = OfflineCache(context)
      override val preferencesStore: PreferencesStore = PreferencesStore(context)
      override val pinStore: PinStore = PinStore(context)
      override val wearWidgetSlotsStore: WearWidgetSlotsStore = WearWidgetSlotsStore(context)
      override val widgetStore: WidgetStore
        get() = error("widgetStore not wired in previews")

      // Previews compose TerrazzoApp's root, which builds a
      // LoginController eagerly. Both bindings are pure
      // context-backed wrappers (vault → encrypted DataStore,
      // authService → AppAuth client config) so a real instance
      // is cheap and reaches no network until the user signs in.
      override val tokenVault: TokenVault = TokenVault(context)
      override val remoteUrlStore: ee.schimke.terrazzo.core.network.RemoteUrlStore =
        ee.schimke.terrazzo.core.network.RemoteUrlStore(context)
      // Real LAN policy + engine — both are cheap (no network
      // until something fires a request) and HaAuthService needs
      // the engine in its constructor now.
      override val lanConnectionPolicy: ee.schimke.terrazzo.core.network.LanConnectionPolicy =
        ee.schimke.terrazzo.core.network.LanConnectionPolicy(context)
      private val httpEngineFactory =
        ee.schimke.terrazzo.core.network.HttpEngineFactory(
          policy = lanConnectionPolicy,
          remoteUrlStore = remoteUrlStore,
        )
      override val authService: HaAuthService = HaAuthService(context, httpEngineFactory)
      override val mobileAppStore: MobileAppStore = MobileAppStore(context)
      override val mobileAppRegistrar: MobileAppRegistrar =
        MobileAppRegistrar(context, mobileAppStore, httpEngineFactory)
      override val sessionFactory: HaSessionFactory
        get() = error("sessionFactory not wired in previews")

      override val sessionWriteMode: ee.schimke.terrazzo.core.session.SessionWriteMode =
        ee.schimke.terrazzo.core.session.SessionWriteMode()
      override val cardMonitor: CardMonitor
        get() =
          object : CardMonitor {
            override val isEnabled: Boolean = false

            override fun start(card: CardConfig, durationMinutes: Int) {}
          }

      override val wearSyncManager: WearSyncManager = NoOpWearSyncManager()
      override val logStore: ee.schimke.terrazzo.core.logs.LogStore =
        ee.schimke.terrazzo.core.logs.LogStore(context)
    }
  }
}

@Composable
private fun PhoneHost(
  style: ThemeStyle = ThemeStyle.TerrazzoHome,
  darkMode: DarkModePref = DarkModePref.Follow,
  content: @Composable () -> Unit,
) {
  val graph = rememberPreviewGraph()
  TerrazzoTheme(style = style, darkMode = darkMode) {
    CompositionLocalProvider(LocalTerrazzoGraph provides graph) {
      // Paint the Material 3 surface as the page background — the
      // dashboard cards sit on top, and without an explicit fill
      // the preview's transparent background shows through and
      // makes dark-mode cards look stranded on white.
      Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold { padding -> Box(modifier = Modifier.padding(padding)) { content() } }
      }
    }
  }
}

// --- Original screen previews -------------------------------------------

@Preview(
  name = "discovery",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Discovery() = PhoneHost { DiscoveryScreen(onInstancePicked = {}, onDemoSelected = {}) }

@Preview(
  name = "discovery · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Discovery_ThemeStyle(@PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle) =
  PhoneHost(style = style) { DiscoveryScreen(onInstancePicked = {}, onDemoSelected = {}) }

@Preview(
  name = "widgets",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Widgets() = PhoneHost { WidgetsScreen(onBack = {}) }

@Preview(
  name = "widgets · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Widgets_ThemeStyle(@PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle) =
  PhoneHost(style = style) { WidgetsScreen(onBack = {}) }

@Preview(
  name = "dashboard picker",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardPicker() = PhoneHost {
  DashboardPickerScreen(
    state =
      DashboardListState.Ready(
        dashboards =
          listOf(
            DashboardSummary(urlPath = null, title = "Home"),
            DashboardSummary(urlPath = "lovelace-mobile", title = "Living room"),
            DashboardSummary(urlPath = "lovelace-garage", title = "Garage"),
          )
      ),
    onDashboardPicked = {},
    // The real screen hosts the picker inside a Scaffold and hands
    // it the top-bar inset as contentPadding; mirror that here so
    // the first card isn't clipped flush against the preview's top
    // edge.
    contentPadding = PaddingValues(vertical = 12.dp),
  )
}

@Preview(
  name = "dashboard picker · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardPicker_ThemeStyle(
  @PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle
) =
  PhoneHost(style = style) {
    DashboardPickerScreen(
      state =
        DashboardListState.Ready(
          dashboards =
            listOf(
              DashboardSummary(urlPath = null, title = "Home"),
              DashboardSummary(urlPath = "lovelace-mobile", title = "Living room"),
              DashboardSummary(urlPath = "lovelace-garage", title = "Garage"),
            )
        ),
      onDashboardPicked = {},
      // The real screen hosts the picker inside a Scaffold and hands
      // it the top-bar inset as contentPadding; mirror that here so
      // the first card isn't clipped flush against the preview's top
      // edge.
      contentPadding = PaddingValues(vertical = 12.dp),
    )
  }

@Preview(
  name = "dashboard view",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardView() = PhoneHost {
  DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _ -> })
}

@Preview(
  name = "dashboard view · phone landscape medium",
  showBackground = false,
  widthDp = PHONE_LANDSCAPE_MEDIUM_WIDTH_DP,
  heightDp = PHONE_LANDSCAPE_MEDIUM_HEIGHT_DP,
)
@Composable
fun Screen_DashboardView_PhoneLandscapeMedium() = PhoneHost {
  DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _ -> })
}

/**
 * Parameterised fan-out of [DashboardViewScreen] over every [ThemeStyle]. Produces one PNG per
 * curated palette so the PR can compare them side-by-side against the same dashboard content.
 */
@Preview(
  name = "dashboard · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardView_ThemeStyle(
  @PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle
) =
  PhoneHost(style = style) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _ -> })
  }

/**
 * Sample card + snapshot for the card-history screen previews. A tile bound to a numeric demo
 * sensor (`sensor.downstairs_temperature`) so the history section renders a populated line chart:
 * [demoSession] synthesizes a plausible series for that entity, and [DemoData.snapshot] supplies
 * its friendly name + unit.
 */
private val HISTORY_SAMPLE_CARD =
  CardConfig(
    type = "tile",
    raw =
      buildJsonObject {
        put("type", "tile")
        put("entity", "sensor.downstairs_temperature")
      },
  )

/**
 * History series for [HISTORY_SAMPLE_CARD], computed synchronously off the same frozen
 * [DEMO_CLOCK_MS] the preview session reads, for the default 24-hour range. Seeding
 * [CardHistoryScreen] with this makes the first composition render the settled chart instead of the
 * async loading spinner, so the screenshot can't capture the spinner mid-fetch (the #409
 * flaky-render investigation).
 */
private val HISTORY_SAMPLE_SERIES: Map<String, List<HistoryPoint>> =
  DemoData.history(HISTORY_SAMPLE_CARD.historyEntityIds(), hours = 24, nowMs = DEMO_CLOCK_MS)

@Preview(
  name = "card history",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_CardHistory() = PhoneHost {
  CardHistoryScreen(
    session = demoSession(),
    card = HISTORY_SAMPLE_CARD,
    snapshot = DemoData.snapshot(),
    onBack = {},
    onAddToHomeScreen = {},
    initialHistory = HISTORY_SAMPLE_SERIES,
  )
}

@Preview(
  name = "card history · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_CardHistory_ThemeStyle(@PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle) =
  PhoneHost(style = style) {
    CardHistoryScreen(
      session = demoSession(),
      card = HISTORY_SAMPLE_CARD,
      snapshot = DemoData.snapshot(),
      onBack = {},
      onAddToHomeScreen = {},
      initialHistory = HISTORY_SAMPLE_SERIES,
    )
  }

/**
 * Sample dashboard list for the selection-screen previews: a couple of named custom dashboards
 * (what HA actually returns from `lovelace/dashboards/list`). The screen itself stitches in the
 * built-in "Overview" entry on top of this.
 */
private val SELECTION_SAMPLE_DASHBOARDS =
  listOf(
    DashboardSummary(urlPath = "lovelace-mobile", title = "Living room"),
    DashboardSummary(urlPath = "lovelace-garage", title = "Garage"),
    DashboardSummary(urlPath = "energy", title = "Energy"),
  )

@Preview(
  name = "dashboard selection · signin",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardSelection_Signin() = PhoneHost {
  DashboardSelectionScreen(
    state = DashboardListState.Ready(SELECTION_SAMPLE_DASHBOARDS),
    initialSelection = null,
    onConfirm = {},
    onBack = null,
  )
}

@Preview(
  name = "dashboard selection · settings",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardSelection_Settings() = PhoneHost {
  DashboardSelectionScreen(
    state = DashboardListState.Ready(SELECTION_SAMPLE_DASHBOARDS),
    initialSelection = setOf(PreferencesStore.DEFAULT_DASHBOARD_SENTINEL, "lovelace-mobile"),
    onConfirm = {},
    onBack = {},
    title = "Manage dashboards",
  )
}

@Preview(
  name = "dashboard selection · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardSelection_ThemeStyle(
  @PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle
) =
  PhoneHost(style = style) {
    DashboardSelectionScreen(
      state = DashboardListState.Ready(SELECTION_SAMPLE_DASHBOARDS),
      initialSelection = null,
      onConfirm = {},
      onBack = null,
    )
  }

/**
 * Settings screen in demo mode. Reads theme / dark-mode / experimental toggles from the preview
 * graph's real (empty) [PreferencesStore], so every row renders at its default value — this is the
 * home of the theme picker (style guide §6.1), so a preview here guards the picker layout and the
 * five palette rows against regressions.
 */
@Preview(
  name = "settings",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Settings() = PhoneHost {
  SettingsScreen(
    session = demoSession(),
    onToggleDemo = {},
    onSignOut = {},
    onBack = {},
    onOpenSyncDiagnostics = {},
    onManageDashboards = {},
  )
}

@Preview(
  name = "settings · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Settings_ThemeStyle(@PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle) =
  PhoneHost(style = style) {
    SettingsScreen(
      session = demoSession(),
      onToggleDemo = {},
      onSignOut = {},
      onBack = {},
      onOpenSyncDiagnostics = {},
      onManageDashboards = {},
    )
  }

// --- Secondary / power-user screens -------------------------------------
//
// These read their data from runtime stores in production; each has a
// stateless `*Content` layer (extracted for previewability) that takes
// hand-rolled fixtures so the populated layout — not just the empty
// state the preview graph's empty stores would produce — is exercised.

// Fixed wall-clock anchor so the log timestamps render identically every
// run (the rows format `HH:mm:ss` from these values).
private const val LOG_ANCHOR_MS = 1_700_000_000_000L

private val SAMPLE_CRASHES =
  listOf(
    LogEntry.Crash(
      timestamp = LOG_ANCHOR_MS,
      threadName = "main",
      summary = "IllegalStateException: no card renderer for type 'custom:mini-graph'",
      stackTrace =
        "java.lang.IllegalStateException: no card renderer…\n  at ee.schimke.ha.rc.RenderChild(RenderChild.kt:48)",
      fatal = true,
    ),
    LogEntry.Crash(
      timestamp = LOG_ANCHOR_MS - 90_000,
      threadName = "DefaultDispatcher-worker-3",
      summary = "JobCancellationException: snapshot flow cancelled",
      stackTrace = "kotlinx.coroutines.JobCancellationException…",
      fatal = false,
    ),
  )

private val SAMPLE_CONNECTIONS =
  listOf(
    LogEntry.Connection(LOG_ANCHOR_MS, LogConnectionStatus.Connected, "homeassistant.local:8123"),
    LogEntry.Connection(LOG_ANCHOR_MS - 20_000, LogConnectionStatus.Connecting, null),
    LogEntry.Connection(
      LOG_ANCHOR_MS - 35_000,
      LogConnectionStatus.Error,
      "handshake timed out after 10s",
    ),
  )

private val SAMPLE_ACTIONS =
  listOf(
    LogEntry.LocalAction(LOG_ANCHOR_MS, "homeassistant.toggle", "light.kitchen"),
    LogEntry.LocalAction(LOG_ANCHOR_MS - 4_000, "cover.open_cover", "cover.garage_door"),
  )

private val SAMPLE_DATA_UPDATES =
  listOf(
    LogEntry.DataUpdate(LOG_ANCHOR_MS, "sensor.living_room_temperature", "21.3", "21.4"),
    LogEntry.DataUpdate(LOG_ANCHOR_MS - 3_000, "binary_sensor.front_door", "off", "on"),
  )

@Preview(
  name = "logs",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Logs() = PhoneHost {
  LogsContent(
    crashes = SAMPLE_CRASHES,
    connections = SAMPLE_CONNECTIONS,
    actions = SAMPLE_ACTIONS,
    dataUpdates = SAMPLE_DATA_UPDATES,
    onClear = {},
    onBack = {},
  )
}

/**
 * Dark variant — guards that the semantic status colours ([ee.schimke.terrazzo.ui.statusColors])
 * stay legible on a dark surface (the severity chips and crash text used to be baked light-only hex
 * literals).
 */
@Preview(
  name = "logs · dark",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Logs_Dark() =
  PhoneHost(darkMode = DarkModePref.Dark) {
    LogsContent(
      crashes = SAMPLE_CRASHES,
      connections = SAMPLE_CONNECTIONS,
      actions = SAMPLE_ACTIONS,
      dataUpdates = SAMPLE_DATA_UPDATES,
      onClear = {},
      onBack = {},
    )
  }

@Preview(
  name = "logs · empty",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Logs_Empty() = PhoneHost {
  LogsContent(
    crashes = emptyList(),
    connections = emptyList(),
    actions = emptyList(),
    dataUpdates = emptyList(),
    onClear = {},
    onBack = {},
  )
}

private const val SAMPLE_BASE_URL = "http://homeassistant.local:8123"

private val SAMPLE_PIN_ITEMS: List<PinRowItem> =
  listOf(
    PinRowItem.Card(
      MobilePinnedCard(
        key = "card-living",
        baseUrl = SAMPLE_BASE_URL,
        dashboardUrlPath = "lovelace-mobile",
        card = PinnedCardData(type = "tile", title = "Living Room"),
        orderIndex = 0,
      )
    ),
    PinRowItem.Section(
      MobilePinnedSection(
        key = "section-cameras",
        baseUrl = SAMPLE_BASE_URL,
        dashboardUrlPath = "lovelace-garage",
        viewPath = "0",
        sectionIndex = 1,
        title = "Outdoor cameras",
        cards = listOf(PinnedCardData(), PinnedCardData()),
        orderIndex = 1,
      )
    ),
    PinRowItem.Card(
      MobilePinnedCard(
        key = "card-thermostat",
        baseUrl = SAMPLE_BASE_URL,
        dashboardUrlPath = "",
        card = PinnedCardData(type = "thermostat", title = ""),
        orderIndex = 2,
      )
    ),
  )

@Preview(
  name = "manage pinned",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_ManagePinned() = PhoneHost {
  ManagePinnedContent(items = SAMPLE_PIN_ITEMS, onReorder = {}, onUnpin = {}, onBack = {})
}

@Preview(
  name = "manage pinned · empty",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_ManagePinned_Empty() = PhoneHost {
  ManagePinnedContent(items = emptyList(), onReorder = {}, onUnpin = {}, onBack = {})
}

private val SAMPLE_WEAR_CARDS =
  listOf(
    MobilePinnedCard(
      "card-living",
      SAMPLE_BASE_URL,
      "lovelace-mobile",
      PinnedCardData(type = "tile", title = "Living Room"),
      0,
    ),
    MobilePinnedCard(
      "card-garage",
      SAMPLE_BASE_URL,
      "lovelace-garage",
      PinnedCardData(type = "cover", title = "Garage Door"),
      1,
    ),
  )

private val SAMPLE_WEAR_SLOTS =
  listOf(
    WearWidgetSlot(slotIndex = 0, cardKey = "card-living", size = SlotSize.Both),
    WearWidgetSlot(slotIndex = 1, cardKey = "card-garage", size = SlotSize.SmallOnly),
    WearWidgetSlot(slotIndex = 2, cardKey = ""),
    WearWidgetSlot(slotIndex = 3, cardKey = ""),
    WearWidgetSlot(slotIndex = 4, cardKey = ""),
  )

@Preview(
  name = "wear widgets",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_WearWidgets() = PhoneHost {
  WearWidgetsContent(
    slots = SAMPLE_WEAR_SLOTS,
    pinnedCards = SAMPLE_WEAR_CARDS,
    onAssign = { _, _ -> },
    onClear = {},
    onSizeChange = { _, _ -> },
    onBack = {},
  )
}

private val SAMPLE_SYNC_STATS =
  SyncStats(
    datastoreWrites = 128,
    messageSends = 342,
    recentSendMs = listOf(0L, 1_500L, 2_800L, 4_100L, 5_600L, 7_000L, 8_300L, 9_900L),
  )

@Preview(
  name = "sync diagnostics",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_SyncDiagnostics() = PhoneHost {
  SyncDiagnosticsContent(stats = SAMPLE_SYNC_STATS, streamActive = true, onReset = {}, onBack = {})
}

class ThemeStyleProvider : PreviewParameterProvider<ThemeStyle> {
  override val values: Sequence<ThemeStyle> = ThemeStyle.entries.asSequence()
}

// --- Play Store listing graphics ----------------------------------------
//
// The PNGs these emit are copied into
// `app/src/main/play/listings/en-GB/graphics/{phone,seven-inch,ten-inch}-screenshots/`
// by `scripts/render-play-screenshots.sh`. Filenames there encode the
// listing slot (01, 02, …) and the upload order, so the function names
// below are picked to sort cleanly.

/** Phone slot 1 — light dashboard (the "home screen"). */
@Preview(name = "play · phone · home (light)", showBackground = false, device = PIXEL_2_DEVICE)
@Composable
fun Play_Phone_01_HomeLight() =
  PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _ -> })
  }

/** Phone slot 2 — dark dashboard (the "home screen"). */
@Preview(name = "play · phone · home (dark)", showBackground = false, device = PIXEL_2_DEVICE)
@Composable
fun Play_Phone_02_HomeDark() =
  PhoneHost(darkMode = DarkModePref.Dark) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _ -> })
  }

/** Phone slot 3 — discovery / first-launch flow. */
@Preview(name = "play · phone · discovery", showBackground = false, device = PIXEL_2_DEVICE)
@Composable
fun Play_Phone_03_Discovery() =
  PhoneHost(darkMode = DarkModePref.Light) {
    DiscoveryScreen(onInstancePicked = {}, onDemoSelected = {})
  }

/** Phone slot 4 — multi-dashboard picker. */
@Preview(name = "play · phone · picker", showBackground = false, device = PIXEL_2_DEVICE)
@Composable
fun Play_Phone_04_Picker() =
  PhoneHost(darkMode = DarkModePref.Light) {
    DashboardPickerScreen(
      state =
        DashboardListState.Ready(
          dashboards =
            listOf(
              DashboardSummary(urlPath = null, title = "Home"),
              DashboardSummary(urlPath = "lovelace-mobile", title = "Living room"),
              DashboardSummary(urlPath = "lovelace-garage", title = "Garage"),
            )
        ),
      onDashboardPicked = {},
      // The real screen hosts the picker inside a Scaffold and hands
      // it the top-bar inset as contentPadding; mirror that here so
      // the first card isn't clipped flush against the preview's top
      // edge.
      contentPadding = PaddingValues(vertical = 12.dp),
    )
  }

/** Phone slot 5 — installed widgets. */
@Preview(name = "play · phone · widgets", showBackground = false, device = PIXEL_2_DEVICE)
@Composable
fun Play_Phone_05_Widgets() =
  PhoneHost(darkMode = DarkModePref.Light) { WidgetsScreen(onBack = {}) }

/** 7-inch tablet slot 1 — home (dashboard view). */
@Preview(name = "play · 7-inch · home", showBackground = false, device = TABLET_7_DEVICE)
@Composable
fun Play_Tablet7_01_Home() =
  PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _ -> })
  }

/** 10-inch tablet slot 1 — home (dashboard view). */
@Preview(name = "play · 10-inch · home", showBackground = false, device = TABLET_10_DEVICE)
@Composable
fun Play_Tablet10_01_Home() =
  PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _ -> })
  }
