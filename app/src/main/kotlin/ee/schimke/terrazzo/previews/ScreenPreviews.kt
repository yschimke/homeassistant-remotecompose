package ee.schimke.terrazzo.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.AssistantAccessSetting
import ee.schimke.terrazzo.CardStyleSection
import ee.schimke.terrazzo.CardStyleState
import ee.schimke.terrazzo.ConnectionStatus
import ee.schimke.terrazzo.DashboardTopBar
import ee.schimke.terrazzo.LocalHaImageStack
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.SettingsScreen
import ee.schimke.terrazzo.UsageSharingSetting
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
import ee.schimke.terrazzo.core.prefs.CardStylePref
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
import ee.schimke.terrazzo.dashboard.DashboardSwitcher
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.dashboard.EntityMoreInfo
import ee.schimke.terrazzo.dashboard.ManagePinnedContent
import ee.schimke.terrazzo.dashboard.PinRowItem
import ee.schimke.terrazzo.dashboard.ProvideScreenSkin
import ee.schimke.terrazzo.dashboard.TopBarOverflowMenu
import ee.schimke.terrazzo.dashboard.currentScreenSkin
import ee.schimke.terrazzo.dashboard.historyEntityIds
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.image.HaImageStack
import ee.schimke.terrazzo.logs.LogsContent
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.wearsync.SyncDiagnosticsContent
import ee.schimke.terrazzo.wearsync.proto.SyncStats
import ee.schimke.terrazzo.widget.InstalledWidget
import ee.schimke.terrazzo.widget.WidgetsContent
import ee.schimke.terrazzo.widget.cardTypeLabel
import kotlin.time.Instant
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
  cardStyle: HaUberTheme = HaUberTheme.Classic,
  content: @Composable () -> Unit,
) {
  val graph = rememberPreviewGraph()
  // The app's image stack, as MainActivity provides it: its loader draws demo mode's stand-in
  // pictures (camera frames, image entities) for `demo://` URLs, which Coil's default can't fetch.
  val context = LocalContext.current.applicationContext
  val imageStack =
    remember(context) { HaImageStack(context, graph.lanConnectionPolicy, graph.remoteUrlStore) }
  TerrazzoTheme(style = style, darkMode = darkMode, cardStyle = cardStyle) {
    CompositionLocalProvider(
      LocalTerrazzoGraph provides graph,
      LocalHaImageStack provides imageStack,
    ) {
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

/** The connect screen with the live demo server's sign-in open, its guest account filled in. */
@Preview(
  name = "discovery · live demo",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Discovery_LiveDemo() = PhoneHost {
  ee.schimke.terrazzo.discovery.DiscoveryForm(
    instances = emptyList(),
    host = "http://homeassistant:8123",
    onHostChange = {},
    onConnect = {},
    onInstancePicked = {},
    onDemoSelected = {},
    initiallyShowLiveDemo = true,
  )
}

@Preview(
  name = "discovery · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Discovery_ThemeStyle(@PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle) =
  PhoneHost(style = style) { DiscoveryScreen(onInstancePicked = {}, onDemoSelected = {}) }

@Preview(name = "card actions", showBackground = false, widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_CardActions() = PhoneHost {
  CardActionsPreview(
    ee.schimke.terrazzo.dashboard.CardActionsState(
      title = "Kitchen",
      stateLabel = "Kitchen lights · On",
      widgetsInUse = 2,
    )
  )
}

/** A card already on the home screen and the watch, in a style of its own. */
@Preview(name = "card actions · placed", showBackground = false, widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_CardActions_Placed() = PhoneHost {
  CardActionsPreview(
    ee.schimke.terrazzo.dashboard.CardActionsState(
      title = "Front door",
      stateLabel = "Closed",
      homeScreenCount = 1,
      widgetsInUse = 5,
      onWatch = true,
      style = ee.schimke.terrazzo.dashboard.CardStyleLabel("Braun", custom = true),
      homeAssistantUrl = "https://ha.example/history?entity_id=lock.front_door",
      canMonitor = true,
    )
  )
}

/** The card actions in every card style: the sheet skins itself in the card's style. */
@Preview(name = "card actions · card style", showBackground = false, widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_CardActions_CardStyle(
  @PreviewParameter(CardStyleProvider::class) cardStyle: HaUberTheme
) =
  PhoneHost(cardStyle = cardStyle) {
    CardActionsPreview(
      ee.schimke.terrazzo.dashboard.CardActionsState(
        title = "Front door",
        stateLabel = "Closed",
        homeScreenCount = 1,
        widgetsInUse = 5,
        onWatch = true,
        style = ee.schimke.terrazzo.dashboard.CardStyleLabel(cardStyle.displayName, custom = false),
        homeAssistantUrl = "https://ha.example/history?entity_id=lock.front_door",
        canMonitor = true,
      )
    )
  }

/** The card actions as the sheet draws them: skinned, on the skin's sheet colour. */
@Composable
private fun CardActionsPreview(state: ee.schimke.terrazzo.dashboard.CardActionsState) =
  ProvideScreenSkin {
    val skin = currentScreenSkin()
    Surface(shape = skin.sheetShape, color = skin.background) {
      Column(Modifier.padding(top = 16.dp)) {
        ee.schimke.terrazzo.dashboard.CardActions(
          state = state,
          onAddToHomeScreen = {},
          onWatchChange = {},
          onChangeStyle = {},
          onShowHistory = {},
          onOpenInHomeAssistant = {},
          onOpenInStudio = {},
          onMonitor = {},
        )
      }
    }
  }

/**
 * A card whose document threw while it played (RcErrorBoundary): the atomic-symbol warning takes
 * its place, and the card beside it plays on.
 */
@Preview(name = "card disabled", showBackground = false, widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_CardDisabled() = PhoneHost {
  androidx.compose.foundation.layout.Column(
    androidx.compose.ui.Modifier.padding(16.dp),
    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
  ) {
    ee.schimke.terrazzo.dashboard.StudioCardPreview(
      demoSession(),
      HISTORY_SAMPLE_CARD,
      DemoData.snapshot(DEMO_CLOCK_MS),
    )
    ee.schimke.terrazzo.shared.RcDisabledWarning("IllegalStateException: measure failed")
    // Safe mode, after a crash: a card waiting for its background check.
    ee.schimke.terrazzo.shared.RcCheckingPlaceholder()
  }
}

@Preview(name = "card style", showBackground = false, widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_CardStyle() = PhoneHost {
  ee.schimke.terrazzo.dashboard.CardStylePicker(
    card = HISTORY_SAMPLE_CARD,
    overrides = ee.schimke.ha.client.design.CardDesignOverrides(),
    dashboardStyle = ee.schimke.ha.rc.components.HaUberTheme.Classic,
    onPick = { _, _ -> },
    onDone = {},
  ) {
    ee.schimke.terrazzo.dashboard.StudioCardPreview(
      demoSession(),
      HISTORY_SAMPLE_CARD,
      DemoData.snapshot(DEMO_CLOCK_MS),
    )
  }
}

/**
 * The card style sheet with the server's skins for the card listed: the library's, an installed
 * device skin for the card's boiler (chosen for this card), a fork that isn't installed and one
 * downloading.
 */
@Preview(name = "card style · skins", showBackground = false, widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_CardStyle_Skins() = PhoneHost {
  val boilerDial = "user:boiler-dial"
  ee.schimke.terrazzo.dashboard.CardStylePicker(
    card = HISTORY_SAMPLE_CARD,
    overrides =
      ee.schimke.ha.client.design
        .CardDesignOverrides()
        .with(
          ee.schimke.ha.client.design.CardDesignScope.Card,
          HISTORY_SAMPLE_CARD,
          ee.schimke.ha.client.design.CardDesignOverride(designId = boilerDial),
        ),
    dashboardStyle = ee.schimke.ha.rc.components.HaUberTheme.Classic,
    onPick = { _, _ -> },
    onDone = {},
    designs =
      listOf(
        ee.schimke.ha.client.design.DesignOption(
          designId = "library:tile",
          title = "Built-in",
          author = ee.schimke.ha.design.DesignEntry.BUILTIN_AUTHOR,
          device = null,
        ),
        ee.schimke.ha.client.design.DesignOption(
          designId = boilerDial,
          title = "Boiler dial",
          author = "github:octocat",
          device = ee.schimke.ha.design.DeviceMatch(manufacturer = "Vaillant", model = "ecoTEC*"),
        ),
        ee.schimke.ha.client.design.DesignOption(
          designId = "user:big-number",
          title = "Big number",
          author = "github:yschimke",
          device = null,
        ),
        ee.schimke.ha.client.design.DesignOption(
          designId = "user:arc-gauge",
          title = "Arc gauge",
          author = "github:octocat",
          device = null,
        ),
      ),
    skinState = {
      when (it) {
        "user:big-number" -> ee.schimke.terrazzo.dashboard.SkinState.NotInstalled
        "user:arc-gauge" -> ee.schimke.terrazzo.dashboard.SkinState.Installing
        else -> ee.schimke.terrazzo.dashboard.SkinState.Installed
      }
    },
  ) {
    ee.schimke.terrazzo.dashboard.StudioCardPreview(
      demoSession(),
      HISTORY_SAMPLE_CARD,
      DemoData.snapshot(DEMO_CLOCK_MS),
    )
  }
}

@Preview(
  name = "widgets",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Widgets() = PhoneHost { WidgetsContent(widgets = emptyList(), onBack = {}) }

@Preview(
  name = "widgets · installed",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Widgets_Installed() = PhoneHost {
  WidgetsContent(
    widgets =
      listOf(
        InstalledWidget("Front door", cardTypeLabel("tile")),
        InstalledWidget("Kitchen", cardTypeLabel("light")),
        InstalledWidget("Living room climate", cardTypeLabel("thermostat")),
      ),
    onBack = {},
  )
}

@Preview(
  name = "widgets · watch",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Widgets_Watch() = PhoneHost {
  val options =
    listOf(
      ee.schimke.terrazzo.core.wearsync.WatchWidgetCardOption("front", "Front door"),
      ee.schimke.terrazzo.core.wearsync.WatchWidgetCardOption("kitchen", "Kitchen"),
    )
  WidgetsContent(
    widgets = listOf(InstalledWidget("Front door", cardTypeLabel("tile"))),
    watchWidgets =
      listOf(
        ee.schimke.terrazzo.core.wearsync.WatchWidget(
          "w",
          "Pixel Watch",
          1,
          false,
          "front",
          options,
        ),
        ee.schimke.terrazzo.core.wearsync.WatchWidget(
          "w",
          "Pixel Watch",
          2,
          true,
          "kitchen",
          options,
        ),
      ),
    onBack = {},
  )
}

@Preview(
  name = "widgets · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Widgets_ThemeStyle(@PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle) =
  PhoneHost(style = style) { WidgetsContent(widgets = emptyList(), onBack = {}) }

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
  DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _, _ -> })
}

@Preview(
  name = "dashboard view · phone landscape medium",
  showBackground = false,
  widthDp = PHONE_LANDSCAPE_MEDIUM_WIDTH_DP,
  heightDp = PHONE_LANDSCAPE_MEDIUM_HEIGHT_DP,
)
@Composable
fun Screen_DashboardView_PhoneLandscapeMedium() = PhoneHost {
  DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _, _ -> })
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
    DashboardViewScreen(session = demoSession(), urlPath = null, onCardLongPress = { _, _, _ -> })
  }

// --- Dashboard top bar ----------------------------------------------------

/** Narrow-phone width: the title used to wrap mid-word ("3D / Printi / ng") here. */
private const val NARROW_PHONE_WIDTH_DP = 360

private val TOP_BAR_DASHBOARDS =
  listOf(
    DashboardSummary(urlPath = null, title = "Home"),
    DashboardSummary(urlPath = "printing", title = "3D Printing"),
    DashboardSummary(urlPath = "garden", title = "Garden irrigation and greenhouse"),
  )

@Composable
private fun TopBarPreviewHost(
  currentUrlPath: String?,
  dashboards: List<DashboardSummary> = TOP_BAR_DASHBOARDS,
  connectionStatus: ConnectionStatus = ConnectionStatus.Connected,
  writeMode: Boolean = true,
) =
  TerrazzoTheme(style = ThemeStyle.TerrazzoHome, darkMode = DarkModePref.Follow) {
    DashboardTopBar(
      title = {
        DashboardSwitcher(dashboards = dashboards, currentUrlPath = currentUrlPath, onSwitch = {})
      },
      notifications = emptyList(),
      onDismissNotification = {},
      onClearNotifications = {},
      connectionStatus = connectionStatus,
      onRetryConnection = {},
      writeMode = writeMode,
      onWriteModeChange = {},
      overflowMenu = {
        TopBarOverflowMenu(
          onOpenSettings = {},
          onOpenWidgets = {},
          onOpenPinned = {},
          onOpenLogs = null,
          onSignOut = {},
          onEditDashboard = {},
        )
      },
    )
  }

@Preview(name = "top bar · 360dp · switcher", widthDp = NARROW_PHONE_WIDTH_DP)
@Composable
fun Screen_DashboardTopBar_Narrow() = TopBarPreviewHost(currentUrlPath = "printing")

@Preview(name = "top bar · 360dp · long title", widthDp = NARROW_PHONE_WIDTH_DP)
@Composable
fun Screen_DashboardTopBar_LongTitle() = TopBarPreviewHost(currentUrlPath = "garden")

@Preview(name = "top bar · 360dp · single dashboard, read only", widthDp = NARROW_PHONE_WIDTH_DP)
@Composable
fun Screen_DashboardTopBar_SingleReadOnly() =
  TopBarPreviewHost(
    currentUrlPath = "printing",
    dashboards = TOP_BAR_DASHBOARDS.filter { it.urlPath == "printing" },
    writeMode = false,
  )

@Preview(name = "top bar · 360dp · connecting", widthDp = NARROW_PHONE_WIDTH_DP)
@Composable
fun Screen_DashboardTopBar_Connecting() =
  TopBarPreviewHost(currentUrlPath = "printing", connectionStatus = ConnectionStatus.Connecting)

@Preview(name = "top bar · 360dp · paused", widthDp = NARROW_PHONE_WIDTH_DP)
@Composable
fun Screen_DashboardTopBar_Paused() =
  TopBarPreviewHost(currentUrlPath = "printing", connectionStatus = ConnectionStatus.Disconnected)

@Preview(name = "top bar · 360dp · failed", widthDp = NARROW_PHONE_WIDTH_DP)
@Composable
fun Screen_DashboardTopBar_Failed() =
  TopBarPreviewHost(currentUrlPath = "printing", connectionStatus = ConnectionStatus.Failed)

@Preview(name = "top bar · 412dp · switcher", widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_DashboardTopBar_Phone() = TopBarPreviewHost(currentUrlPath = "printing")

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
    snapshot = DemoData.snapshot(DEMO_CLOCK_MS),
    onBack = {},
    onShowActions = {},
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
      snapshot = DemoData.snapshot(DEMO_CLOCK_MS),
      onBack = {},
      onShowActions = {},
      initialHistory = HISTORY_SAMPLE_SERIES,
    )
  }

/** The frozen end of the more-info previews' history window: 2026-10-24 09:25 UTC. */
private const val MORE_INFO_NOW_MS = 1_792_833_900_000L

private val MORE_INFO_POWER_STATE =
  EntityState(
    entityId = "sensor.p110_p3_current_consumption",
    state = "128.6",
    attributes =
      buildJsonObject {
        put("friendly_name", "Boilerroom - P110 (P3) Current consumption")
        put("unit_of_measurement", "W")
        put("state_class", "measurement")
        put("device_class", "power")
      },
    lastChanged = Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - 40_000L),
    lastUpdated = Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - 40_000L),
  )

/** A boiler plug's draw: long idle stretches near 2 W, burns at 110–140 W. */
private val MORE_INFO_POWER_HISTORY: List<HistoryPoint> =
  (96 downTo 1).map { k ->
    val t = MORE_INFO_NOW_MS - k * 15 * 60_000L
    val burning = (k / 6) % 3 == 0 || k < 5
    val watts = if (burning) 110 + (k * 37 % 30) else 2 + (k % 3)
    HistoryPoint(ts = Instant.fromEpochMilliseconds(t), state = "$watts")
  }

private val MORE_INFO_SWITCH_STATE =
  EntityState(
    entityId = "switch.p110_p3",
    state = "on",
    attributes =
      buildJsonObject {
        put("friendly_name", "Boiler")
        put("device_class", "outlet")
      },
    lastChanged = Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - 72 * 60_000L),
    lastUpdated = Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - 72 * 60_000L),
  )

/** The boiler switching on and off through the day, with a short unavailable gap. */
private val MORE_INFO_SWITCH_HISTORY: List<HistoryPoint> =
  listOf(
      24 * 60 to "off",
      21 * 60 to "on",
      19 * 60 + 30 to "off",
      16 * 60 to "unavailable",
      15 * 60 + 40 to "off",
      12 * 60 to "on",
      10 * 60 + 15 to "off",
      6 * 60 to "on",
      5 * 60 to "off",
      72 to "on",
    )
    .map { (minutesAgo, state) ->
      HistoryPoint(Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - minutesAgo * 60_000L), state)
    }

/**
 * The more-info sheet as it sits over a dashboard: the lower part of the screen, rounded on top,
 * skinned in the host's card style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreInfoSheetFrame(
  state: EntityState,
  history: List<HistoryPoint>,
  withControls: Boolean = false,
) {
  Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))) {
    ProvideScreenSkin {
      val skin = currentScreenSkin()
      Surface(
        shape = skin.sheetShape,
        color = skin.background,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 760.dp),
      ) {
        Column {
          BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
          EntityMoreInfo(
            entityId = state.entityId,
            state = state,
            loadHistory = { history },
            onOpenHistory = {},
            initialHistory = history,
            nowMs = { MORE_INFO_NOW_MS },
            onAction = if (withControls) ({}) else null,
          )
        }
      }
    }
  }
}

@Preview(
  name = "more info · numeric",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_MoreInfo_Numeric() = PhoneHost {
  MoreInfoSheetFrame(MORE_INFO_POWER_STATE, MORE_INFO_POWER_HISTORY)
}

@Preview(
  name = "more info · on/off",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_MoreInfo_OnOff() = PhoneHost {
  MoreInfoSheetFrame(MORE_INFO_SWITCH_STATE, MORE_INFO_SWITCH_HISTORY)
}

private val MORE_INFO_LIGHT_STATE =
  EntityState(
    entityId = "light.desk",
    state = "on",
    attributes =
      buildJsonObject {
        put("friendly_name", "Desk lamp")
        put("brightness", 166)
        put("color_temp_kelvin", 3200)
        put("min_color_temp_kelvin", 2200)
        put("max_color_temp_kelvin", 6500)
        put(
          "supported_color_modes",
          buildJsonArray { listOf("color_temp", "hs").forEach { add(it) } },
        )
        put("color_mode", "hs")
        put("hs_color", buildJsonArray { listOf(215.0, 80.0).forEach { add(it) } })
      },
    lastChanged = Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - 72 * 60_000L),
    lastUpdated = Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - 72 * 60_000L),
  )

private val MORE_INFO_CLIMATE_STATE =
  EntityState(
    entityId = "climate.hallway",
    state = "heat",
    attributes =
      buildJsonObject {
        put("friendly_name", "Hallway")
        put("supported_features", 17)
        put("temperature", 20.5)
        put("current_temperature", 19.8)
        put("target_temp_step", 0.5)
        put("min_temp", 7)
        put("max_temp", 30)
        put("hvac_modes", buildJsonArray { listOf("off", "heat", "auto").forEach { add(it) } })
        put("preset_modes", buildJsonArray { listOf("home", "away", "eco").forEach { add(it) } })
        put("preset_mode", "home")
      },
    lastChanged = Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - 72 * 60_000L),
    lastUpdated = Instant.fromEpochMilliseconds(MORE_INFO_NOW_MS - 72 * 60_000L),
  )

@Preview(
  name = "more info · light controls",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_MoreInfo_LightControls() = PhoneHost {
  MoreInfoSheetFrame(MORE_INFO_LIGHT_STATE, MORE_INFO_SWITCH_HISTORY, withControls = true)
}

@Preview(
  name = "more info · thermostat controls",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_MoreInfo_ThermostatControls() = PhoneHost {
  MoreInfoSheetFrame(MORE_INFO_CLIMATE_STATE, emptyList(), withControls = true)
}

@Preview(
  name = "more info · numeric · theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_MoreInfo_Numeric_ThemeStyle(
  @PreviewParameter(ThemeStyleProvider::class) style: ThemeStyle
) = PhoneHost(style = style) { MoreInfoSheetFrame(MORE_INFO_POWER_STATE, MORE_INFO_POWER_HISTORY) }

@Preview(
  name = "more info · numeric · card style",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_MoreInfo_Numeric_CardStyle(
  @PreviewParameter(CardStyleProvider::class) cardStyle: HaUberTheme
) =
  PhoneHost(cardStyle = cardStyle) {
    MoreInfoSheetFrame(MORE_INFO_POWER_STATE, MORE_INFO_POWER_HISTORY)
  }

@Preview(
  name = "more info · on/off · card style",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_MoreInfo_OnOff_CardStyle(
  @PreviewParameter(CardStyleProvider::class) cardStyle: HaUberTheme
) =
  PhoneHost(cardStyle = cardStyle) {
    MoreInfoSheetFrame(MORE_INFO_SWITCH_STATE, MORE_INFO_SWITCH_HISTORY)
  }

@Preview(
  name = "more info · on/off · dark",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_MoreInfo_OnOff_Dark() =
  PhoneHost(darkMode = DarkModePref.Dark) {
    MoreInfoSheetFrame(MORE_INFO_SWITCH_STATE, MORE_INFO_SWITCH_HISTORY)
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
    onSignOut = {},
    onBack = {},
    onOpenSyncDiagnostics = {},
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
      onSignOut = {},
      onBack = {},
      onOpenSyncDiagnostics = {},
    )
  }

/** The usage-sharing setting, off (its default) and on. */
@Preview(name = "settings · usage sharing", showBackground = false, widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_Settings_UsageSharing() = PhoneHost {
  Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    UsageSharingSetting(checked = false, onCheckedChange = {})
    UsageSharingSetting(checked = true, onCheckedChange = {})
  }
}

/** Settings → Assistants (AppFunctions), off (its default) and on. */
@Preview(name = "settings · assistants", showBackground = false, widthDp = PHONE_WIDTH_DP)
@Composable
fun Screen_Settings_Assistants() = PhoneHost {
  Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    AssistantAccessSetting(checked = false, onCheckedChange = {})
    AssistantAccessSetting(checked = true, onCheckedChange = {})
  }
}

/**
 * The theme settings under a card style that themes the whole app: the app draws in the style's
 * palette and type, the palette options are disabled and say why, and a dark-only style (Trinitron)
 * disables light / dark too.
 */
@Preview(
  name = "settings · app theme",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Settings_AppTheme(
  @PreviewParameter(AppThemeStyleProvider::class) cardStyle: HaUberTheme
) =
  PhoneHost(cardStyle = cardStyle) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
      ee.schimke.terrazzo.ThemeSection(
        selected = ee.schimke.terrazzo.core.prefs.ThemePref.TerrazzoHome,
        darkMode = DarkModePref.Follow,
        appTheme = cardStyle,
        onSelectTheme = {},
        onSelectDark = {},
      )
    }
  }

/**
 * The card style picker expanded: every style over a sample tile drawn in it, so a regression in
 * any one style's sample shows here. Settings itself opens it collapsed to the current style.
 */
@Preview(
  name = "settings · card styles",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Settings_CardStyles() = PhoneHost {
  Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
    CardStyleSection(selected = CardStylePref.Classic, onSelect = {}, initiallyExpanded = true)
  }
}

/**
 * The card style picker on a new install: Classic and Home Assistant come with the app, an
 * installed style offers Remove, one is downloading, one failed, and the rest offer Install.
 */
@Preview(
  name = "settings · card styles to install",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Settings_CardStylesToInstall() = PhoneHost {
  Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
    CardStyleSection(
      selected = CardStylePref.Classic,
      onSelect = {},
      initiallyExpanded = true,
      state = { pref ->
        when (pref) {
          CardStylePref.Classic,
          CardStylePref.HomeAssistant -> CardStyleState.BuiltIn
          CardStylePref.Expressive -> CardStyleState.Installed
          CardStylePref.Trinitron -> CardStyleState.Installing
          CardStylePref.Lcd -> CardStyleState.Failed
          else -> CardStyleState.NotInstalled
        }
      },
    )
  }
}

/**
 * The card style picker previewed on a watch: the current style's sample as Small and Large Wear
 * widgets on round and square watch screens.
 */
@Preview(
  name = "settings · card styles on a watch",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_Settings_CardStylesOnWatch() = PhoneHost {
  Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
    CardStyleSection(selected = CardStylePref.Lcd, onSelect = {}, initiallyOnWatch = true)
  }
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

/** The card styles that theme the whole app ([HaUberTheme.appTheme]). */
class AppThemeStyleProvider : PreviewParameterProvider<HaUberTheme> {
  private val styles = HaUberTheme.all.filter { it.appTheme }
  override val values: Sequence<HaUberTheme> = styles.asSequence()

  override fun getDisplayName(index: Int): String = styles[index].id
}

/** Every card style ([HaUberTheme]), for the skinned secondary screens. */
class CardStyleProvider : PreviewParameterProvider<HaUberTheme> {
  override val values: Sequence<HaUberTheme> = HaUberTheme.all.asSequence()

  override fun getDisplayName(index: Int): String = HaUberTheme.all[index].id
}

// --- Play Store listing graphics ----------------------------------------
//
// The PNGs these emit are copied into
// `app/src/main/play/listings/en-GB/graphics/{phone,seven-inch,ten-inch}-screenshots/`
// by `scripts/render-play-screenshots.sh`. Filenames there encode the
// listing slot (01, 02, …) and the upload order, so the function names
// below are picked to sort cleanly.

/**
 * The demo board the Play home shots open on. 3D Printing settles in the renderer (no animation
 * left running), has a rendered print bed and no "Unavailable" tiles; Climate, Energy, MeshCore and
 * Networks keep animating, and Security leads with unavailable garage sensors.
 */
private const val PLAY_HOME_BOARD = "3d_printing"

/** Phone slot 1 — light dashboard (the "home screen"). */
@Preview(name = "play · phone · home (light)", showBackground = false, device = PIXEL_2_DEVICE)
@Composable
fun Play_Phone_01_HomeLight() =
  PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(
      session = demoSession(),
      urlPath = PLAY_HOME_BOARD,
      onCardLongPress = { _, _, _ -> },
    )
  }

/** Phone slot 2 — dark dashboard (the "home screen"). */
@Preview(name = "play · phone · home (dark)", showBackground = false, device = PIXEL_2_DEVICE)
@Composable
fun Play_Phone_02_HomeDark() =
  PhoneHost(darkMode = DarkModePref.Dark) {
    DashboardViewScreen(
      session = demoSession(),
      urlPath = PLAY_HOME_BOARD,
      onCardLongPress = { _, _, _ -> },
    )
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
      // The demo's own boards, as the picker lists them in demo mode.
      state = DashboardListState.Ready(dashboards = DemoData.dashboards),
      onDashboardPicked = {},
      // The real screen hosts the picker inside a Scaffold and hands
      // it the top-bar inset as contentPadding; mirror that here so
      // the first card isn't clipped flush against the preview's top
      // edge.
      contentPadding = PaddingValues(vertical = 12.dp),
    )
  }

/** Phone slot 5 — the cards the phone shows on the watch ("Cards on your watch"). */
@Preview(name = "play · phone · watch cards", showBackground = false, device = PIXEL_2_DEVICE)
@Composable
fun Play_Phone_05_WatchCards() =
  PhoneHost(darkMode = DarkModePref.Light) {
    ManagePinnedContent(items = SAMPLE_PIN_ITEMS, onReorder = {}, onUnpin = {}, onBack = {})
  }

/** 7-inch tablet slot 1 — home (dashboard view). */
@Preview(name = "play · 7-inch · home", showBackground = false, device = TABLET_7_DEVICE)
@Composable
fun Play_Tablet7_01_Home() =
  PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(
      session = demoSession(),
      urlPath = PLAY_HOME_BOARD,
      onCardLongPress = { _, _, _ -> },
    )
  }

/** 10-inch tablet slot 1 — home (dashboard view). */
@Preview(name = "play · 10-inch · home", showBackground = false, device = TABLET_10_DEVICE)
@Composable
fun Play_Tablet10_01_Home() =
  PhoneHost(darkMode = DarkModePref.Light) {
    DashboardViewScreen(
      session = demoSession(),
      urlPath = PLAY_HOME_BOARD,
      onCardLongPress = { _, _, _ -> },
    )
  }

/** A small sections dashboard over the demo climate entities, for the dashboard editor previews. */
private val EDITOR_SAMPLE_CONFIG =
  kotlinx.serialization.json.Json.parseToJsonElement(
      """
      {"title":"Climate","views":[{"title":"Home","type":"sections","sections":[
        {"type":"grid","cards":[
          {"type":"heading","heading":"Rooms"},
          {"type":"tile","entity":"sensor.downstairs_temperature","name":"Downstairs"},
          {"type":"tile","entity":"sensor.upstairs_temperature","name":"Upstairs"},
          {"type":"tile","entity":"sensor.kids_room_temperature","name":"Kids room",
            "grid_options":{"columns":"full"}},
          {"type":"tile","entity":"sensor.downstairs_humidity","name":"Humidity",
            "grid_options":{"columns":9}}
        ]},
        {"type":"grid","cards":[
          {"type":"heading","heading":"Outside"},
          {"type":"weather-forecast","entity":"weather.forecast_home"}
        ]}
      ]}]}
      """
    )
    .let { it as kotlinx.serialization.json.JsonObject }

@Composable
private fun EditorPreviewHost(selection: ee.schimke.ha.client.dashboard.CardAddress?) = PhoneHost {
  val session = remember { demoSession() }
  val edits = remember {
    ee.schimke.ha.client.dashboard.DashboardEditSession(
      EDITOR_SAMPLE_CONFIG,
      readOnlyReason = null,
    ) { _, _ ->
    }
  }
  ee.schimke.terrazzo.dashboard.DashboardEditor(
    session = session,
    edits = edits,
    title = "Climate",
    snapshot = remember { session.dashboardNow("climate").second },
    snackbars = remember { androidx.compose.material3.SnackbarHostState() },
    onBack = {},
    onSaved = {},
    launch = {},
    initialSelection = selection,
  )
}

@Preview(
  name = "dashboard editor",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardEditor() = EditorPreviewHost(selection = null)

@Preview(
  name = "dashboard editor · card selected",
  showBackground = false,
  widthDp = PHONE_WIDTH_DP,
  heightDp = PHONE_HEIGHT_DP,
)
@Composable
fun Screen_DashboardEditor_Selected() =
  EditorPreviewHost(selection = ee.schimke.ha.client.dashboard.CardAddress(0, 0, 4))
