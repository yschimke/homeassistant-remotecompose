package ee.schimke.terrazzo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.auth.rememberLoginController
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardSwitcher
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.dashboard.TopBarOverflowMenu
import ee.schimke.terrazzo.dashboard.rememberDashboardListState
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.monitor.MonitoringService
import ee.schimke.terrazzo.widget.WidgetInstallSheet
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import ee.schimke.terrazzo.widget.WidgetsScreen
import kotlinx.coroutines.launch

/**
 * Root composable. Two phases:
 *
 * 1. **Unauthenticated**: discovery + login flow (one screen, no chrome).
 *    Completing login — or enabling demo mode — promotes to phase 2.
 * 2. **Authenticated**: a dashboard-first shell. The dashboard fills the
 *    screen; a small `TopAppBar` carries a [DashboardSwitcher] (current
 *    dashboard name + chevron + 1-tap-switch dropdown) and an overflow
 *    menu hosting Settings / Manage widgets / Sign out. No more
 *    `NavigationSuiteScaffold` — there's nothing to navigate to other
 *    than dashboards, and Settings / widgets are rare visits hidden
 *    behind one tap of the overflow.
 *
 * Demo mode (toggled from Settings, or from the Discovery "try demo"
 * button) swaps the live [HaSession] for a deterministic fake so the
 * app can be explored without a working HA instance.
 */
@Composable
fun TerrazzoApp(
    initialDashboard: String? = null,
    initialSession: HaSession? = null,
) {
    // Cold-start auto-resume: [initialSession] is a cache-only session
    // built in MainActivity from the last-known instance, so the first
    // frame paints from disk while we re-mint the access token in the
    // background. A new sign-in or demo toggle replaces this state.
    var session by remember { mutableStateOf(initialSession) }
    var lastError by remember { mutableStateOf<Throwable?>(null) }

    val graph = LocalTerrazzoGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widgetScheduler = remember(context) { WidgetRefreshScheduler(context.applicationContext) }
    val wearSync = remember(context) {
        (context.applicationContext as TerrazzoApplication).wearSync
    }

    // Persisted demo-mode flag survives process restarts, so a user who
    // opted into demo doesn't see the login screen again on relaunch.
    // Also re-arms the widget refresh chain in case the worker queue
    // was flushed (e.g. "Clear data" via system Settings). When a
    // cache-only [initialSession] was passed in (cold-start auto-resume
    // from a prior login), the demo path overrides it.
    LaunchedEffect(Unit) {
        if (graph.preferencesStore.demoModeNow()) {
            session = graph.sessionFactory.createDemo()
            widgetScheduler.scheduleDemo()
        }
    }

    // Mirror the active session to the wear data layer so the watch
    // can render dashboards / live values from whatever's current.
    LaunchedEffect(session) { wearSync.setSession(session) }

    val login = rememberLoginController(
        onReady = { baseUrl, accessToken ->
            session = graph.sessionFactory.create(baseUrl, accessToken)
        },
        onError = { lastError = it },
    )

    when (val s = session) {
        null -> UnauthenticatedScreen(
            onInstancePicked = { login.start(it) },
            onDemoSelected = {
                scope.launch { graph.preferencesStore.setDemoMode(true) }
                session = graph.sessionFactory.createDemo()
                widgetScheduler.scheduleDemo()
            },
            error = lastError,
        )
        else -> AuthenticatedShell(
            session = s,
            initialDashboard = initialDashboard,
            onToggleDemo = { enabled ->
                val previous = session
                scope.launch {
                    graph.preferencesStore.setDemoMode(enabled)
                    // The dashboard the user just looked at belongs
                    // to the previous identity — clear so the next
                    // launch goes through the picker rather than
                    // jumping into a dashboard that may not exist on
                    // the new instance.
                    graph.preferencesStore.clearLastViewedDashboard()
                    previous?.close()
                }
                if (enabled) {
                    session = graph.sessionFactory.createDemo()
                    widgetScheduler.scheduleDemo()
                } else {
                    session = null
                    widgetScheduler.cancelDemo()
                }
            },
            onSignOut = {
                val previous = session
                val previousBaseUrl = previous?.baseUrl
                scope.launch {
                    graph.preferencesStore.setDemoMode(false)
                    graph.preferencesStore.clearLastViewedDashboard()
                    // Wipe cached dashboards / snapshots and the
                    // refresh-token vault entry so the next user on
                    // this device can't read the previous account's
                    // data, and so cold start goes back to discovery.
                    if (previousBaseUrl != null) {
                        graph.offlineCache.clearInstance(previousBaseUrl)
                        runCatching { graph.tokenVault.clear(previousBaseUrl) }
                    }
                    previous?.close()
                }
                widgetScheduler.cancelDemo()
                session = null
            },
        )
    }
}

@Composable
private fun UnauthenticatedScreen(
    onInstancePicked: (String) -> Unit,
    onDemoSelected: () -> Unit,
    error: Throwable?,
) {
    DiscoveryScreen(
        onInstancePicked = onInstancePicked,
        onDemoSelected = onDemoSelected,
    )
    error?.let { err ->
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Login failed", style = MaterialTheme.typography.titleMedium)
            Text(err.message ?: err::class.simpleName.orEmpty())
        }
    }
}

private enum class AppScreen { Dashboards, Settings, Widgets, SyncDiagnostics }

@Composable
private fun AuthenticatedShell(
    session: HaSession,
    initialDashboard: String?,
    onToggleDemo: (Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.Dashboards) }

    // System-back from Settings / Widgets returns to the dashboards
    // root. Inside DashboardsRoot another BackHandler routes view →
    // picker; the platform handles back at the picker (exits app).
    BackHandler(enabled = screen != AppScreen.Dashboards) {
        screen = if (screen == AppScreen.SyncDiagnostics) AppScreen.Settings else AppScreen.Dashboards
    }

    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as TerrazzoApplication }

    when (screen) {
        AppScreen.Dashboards -> DashboardsRoot(
            session = session,
            initialDashboard = initialDashboard,
            onOpenSettings = { screen = AppScreen.Settings },
            onOpenWidgets = { screen = AppScreen.Widgets },
            onSignOut = onSignOut,
        )
        AppScreen.Settings -> SettingsScreen(
            session = session,
            onToggleDemo = onToggleDemo,
            onSignOut = onSignOut,
            onBack = { screen = AppScreen.Dashboards },
            onOpenSyncDiagnostics = { screen = AppScreen.SyncDiagnostics },
        )
        AppScreen.Widgets -> WidgetsScreen(
            onBack = { screen = AppScreen.Dashboards },
        )
        AppScreen.SyncDiagnostics -> {
            val streaming by app.wearSync.streamActive.collectAsState()
            ee.schimke.terrazzo.wearsync.SyncDiagnosticsScreen(
                statsStore = app.syncStats,
                streamActive = streaming,
                onBack = { screen = AppScreen.Settings },
            )
        }
    }
}

/**
 * The dashboard-first shell. Loads the dashboards list once for the
 * session, hosts the picker → view local nav, and renders the
 * [TopAppBar] with the [DashboardSwitcher] + [TopBarOverflowMenu].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardsRoot(
    session: HaSession,
    initialDashboard: String?,
    onOpenSettings: () -> Unit,
    onOpenWidgets: () -> Unit,
    onSignOut: () -> Unit,
) {
    val graph = LocalTerrazzoGraph.current
    val scope = rememberCoroutineScope()
    val dashboards by rememberDashboardListState(session)

    var opened by rememberSaveable {
        mutableStateOf(initialDashboardToOpened(initialDashboard))
    }
    var installPending by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
    val openedValue = opened

    // System-back: view → picker; on the picker the platform handles it.
    BackHandler(enabled = openedValue != DASHBOARD_UNSET) {
        opened = DASHBOARD_UNSET
    }

    val readyDashboards = (dashboards as? DashboardListState.Ready)?.dashboards.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (openedValue == DASHBOARD_UNSET) {
                        Text("Pick a dashboard")
                    } else {
                        DashboardSwitcher(
                            dashboards = readyDashboards,
                            currentUrlPath = openedValue,
                            onSwitch = { newPath ->
                                opened = newPath
                                scope.launch {
                                    graph.preferencesStore.setLastViewedDashboard(newPath)
                                }
                            },
                        )
                    }
                },
                actions = {
                    TopBarOverflowMenu(
                        onOpenSettings = onOpenSettings,
                        onOpenWidgets = onOpenWidgets,
                        onSignOut = onSignOut,
                    )
                },
            )
        },
        // Scaffold consumes the top inset for the bar; we hand the
        // bottom + sides to the content as the inner contentPadding.
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        if (openedValue == DASHBOARD_UNSET) {
            DashboardPickerScreen(
                state = dashboards,
                onDashboardPicked = { urlPath ->
                    opened = urlPath
                    // Persist for the next cold start. The picker
                    // and the switcher are the only entry points
                    // that change which dashboard is "current".
                    scope.launch { graph.preferencesStore.setLastViewedDashboard(urlPath) }
                },
                contentPadding = padding,
            )
        } else {
            DashboardViewScreen(
                session = session,
                urlPath = openedValue,
                onCardLongPress = { card ->
                    // In demo mode the preview — and the installed widget —
                    // render against the current fake snapshot so values
                    // aren't empty. Live mode uses an empty snapshot; the
                    // pinned widget will refresh from HA on first tick.
                    val previewSnapshot =
                        if (session is DemoHaSession) DemoData.snapshot() else HaSnapshot()
                    installPending = card to previewSnapshot
                },
                contentPadding = padding,
            )
        }
    }

    val monitorContext = LocalContext.current
    installPending?.let { (card, snapshot) ->
        WidgetInstallSheet(
            baseUrl = session.baseUrl,
            card = card,
            snapshot = snapshot,
            onDismiss = { installPending = null },
            // Only offer monitoring in demo mode — the service pulls
            // state from DemoData today. Live mode needs a shared
            // non-UI HA session; landing in a follow-up.
            onMonitor = if (session is DemoHaSession) {
                { MonitoringService.start(monitorContext.applicationContext, card) }
            } else null,
        )
    }
}

/** Sentinel for "no dashboard opened yet". null is a valid urlPath (the default dashboard). */
private const val DASHBOARD_UNSET = "__none__"

/**
 * Translate the persisted last-viewed-dashboard pref into the local
 * `opened` sentinel/urlPath encoding `DashboardsRoot` uses:
 *
 *   - `null` (pref absent) → [DASHBOARD_UNSET] (show picker).
 *   - [PreferencesStore.DEFAULT_DASHBOARD_SENTINEL] → `null`
 *     (HA's default dashboard, whose `urlPath` is null over the wire).
 *   - any other string → that `urlPath`.
 */
private fun initialDashboardToOpened(stored: String?): String? = when (stored) {
    null -> DASHBOARD_UNSET
    PreferencesStore.DEFAULT_DASHBOARD_SENTINEL -> null
    else -> stored
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    session: HaSession,
    onToggleDemo: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onOpenSyncDiagnostics: () -> Unit,
) {
    val isDemo = session is DemoHaSession
    val graph = LocalTerrazzoGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widgetRefresh = remember(context) { WidgetRefreshScheduler(context.applicationContext) }
    val themePref by graph.preferencesStore.themeStyle.collectAsState(initial = ThemePref.TerrazzoHome)
    val darkPref by graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)
    val gridLayoutEnabled by graph.preferencesStore.experimentalGridLayout.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connected to", style = MaterialTheme.typography.labelMedium)
            Text(
                if (isDemo) "Demo mode — offline fake data" else session.baseUrl,
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Populate the app with an interesting set of fake widgets that animate.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = isDemo,
                    onCheckedChange = onToggleDemo,
                )
            }

            ThemeSection(
                selected = themePref,
                darkMode = darkPref,
                onSelectTheme = { pref ->
                    scope.launch {
                        graph.preferencesStore.setThemeStyle(pref)
                        // Each pinned widget has a baked .rc doc using the
                        // old palette; broadcast a refresh so the provider
                        // re-captures under the new one.
                        widgetRefresh.refreshAllNow()
                    }
                },
                onSelectDark = { pref ->
                    scope.launch {
                        graph.preferencesStore.setDarkMode(pref)
                        widgetRefresh.refreshAllNow()
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Experimental: Grid layout", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use Compose 1.11's Grid API for the wide-screen side-by-side " +
                            "section layout instead of the chunked Row path. Only takes " +
                            "effect on tablets / unfolded foldables with ≥2 sections.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = gridLayoutEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { graph.preferencesStore.setExperimentalGridLayout(enabled) }
                    },
                )
            }

            OutlinedButton(onClick = onSignOut) {
                Text(if (isDemo) "Exit demo" else "Sign out")
            }

            // Sync diagnostics — buried at the bottom of Settings on
            // purpose. Shows DataItem write / MessageClient send counts
            // so power users can sanity-check wear-side chatter.
            androidx.compose.material3.TextButton(onClick = onOpenSyncDiagnostics) {
                Text("Sync diagnostics")
            }
        }
    }
}

@Composable
private fun ThemeSection(
    selected: ThemePref,
    darkMode: DarkModePref,
    onSelectTheme: (ThemePref) -> Unit,
    onSelectDark: (DarkModePref) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Text(
            "Material 3 uses the system default palette (dynamic colour on Android 12+). " +
                "The Terrazzo themes ship a curated Home-Assistant-flavoured palette plus a Google-Fonts pairing.",
            style = MaterialTheme.typography.bodySmall,
        )
        ThemePref.entries.forEach { pref ->
            ThemeRow(
                pref = pref,
                selected = pref == selected,
                onClick = { onSelectTheme(pref) },
            )
        }

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DarkModePref.entries.forEach { pref ->
                FilterChip(
                    selected = pref == darkMode,
                    onClick = { onSelectDark(pref) },
                    label = {
                        Text(
                            when (pref) {
                                DarkModePref.Follow -> "Follow system"
                                DarkModePref.Light -> "Light"
                                DarkModePref.Dark -> "Dark"
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(pref: ThemePref, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(themePrefDisplayName(pref), style = MaterialTheme.typography.bodyLarge)
            Text(themePrefTagline(pref), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun themePrefDisplayName(pref: ThemePref): String = when (pref) {
    ThemePref.Material3 -> "Material 3"
    ThemePref.TerrazzoHome -> "Home"
    ThemePref.TerrazzoMushroom -> "Mushroom"
    ThemePref.TerrazzoMinimalist -> "Minimalist"
    ThemePref.TerrazzoKiosk -> "Kiosk"
}

private fun themePrefTagline(pref: ThemePref): String = when (pref) {
    ThemePref.Material3 -> "Defaults · dynamic colour on Android 12+"
    ThemePref.TerrazzoHome -> "Home Assistant blue · Roboto Flex + Inter"
    ThemePref.TerrazzoMushroom -> "Warm salmon · Figtree"
    ThemePref.TerrazzoMinimalist -> "Neutral slate · IBM Plex Sans"
    ThemePref.TerrazzoKiosk -> "High-contrast teal · Atkinson Hyperlegible"
}
