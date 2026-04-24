package ee.schimke.terrazzo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
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
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.monitor.MonitoringService
import ee.schimke.terrazzo.widget.WidgetInstallSheet
import ee.schimke.terrazzo.widget.WidgetsScreen
import kotlinx.coroutines.launch

/**
 * Root composable. Two phases:
 *
 * 1. **Unauthenticated**: discovery + login flow (one screen, not in
 *    the nav suite). Completing login — or enabling demo mode —
 *    promotes to phase 2.
 * 2. **Authenticated**: `NavigationSuiteScaffold` with three top-level
 *    destinations — Dashboards, Widgets, Settings. The suite picks
 *    bottom bar / nav rail / nav drawer based on window size class
 *    (phone / foldable-closed / unfolded / tablet). Within
 *    Dashboards, a lightweight back-stack walks picker → view.
 *
 * Demo mode (toggled from Settings, or from the Discovery "try demo"
 * button) swaps the live [HaSession] for a deterministic fake so the
 * app can be explored without a working HA instance.
 */
@Composable
fun TerrazzoApp() {
    // NOTE: not saveable — an HaSession owns a live WebSocket. Process
    // restart re-walks the discovery / login flow. The refresh token in
    // TokenVault means we can auto-sign-in silently once we wire that up.
    var session by remember { mutableStateOf<HaSession?>(null) }
    var lastError by remember { mutableStateOf<Throwable?>(null) }

    val graph = LocalTerrazzoGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widgetScheduler = remember(context) { WidgetRefreshScheduler(context.applicationContext) }

    // Persisted demo-mode flag survives process restarts, so a user who
    // opted into demo doesn't see the login screen again on relaunch.
    // Also re-arms the widget refresh chain in case the worker queue
    // was flushed (e.g. "Clear data" via system Settings).
    LaunchedEffect(Unit) {
        if (graph.preferencesStore.demoModeNow()) {
            if (session == null) session = graph.sessionFactory.createDemo()
            widgetScheduler.scheduleDemo()
        }
    }

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
        else -> AuthenticatedScaffold(
            session = s,
            onToggleDemo = { enabled ->
                val previous = session
                scope.launch {
                    graph.preferencesStore.setDemoMode(enabled)
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
                scope.launch {
                    graph.preferencesStore.setDemoMode(false)
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

private enum class Destination(val label: String) {
    Dashboards("Dashboards"),
    Widgets("Widgets"),
    Settings("Settings"),
}

@Composable
private fun AuthenticatedScaffold(
    session: HaSession,
    onToggleDemo: (Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    var current by rememberSaveable { mutableStateOf(Destination.Dashboards) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = current == Destination.Dashboards,
                onClick = { current = Destination.Dashboards },
                icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
                label = { Text("Dashboards") },
            )
            item(
                selected = current == Destination.Widgets,
                onClick = { current = Destination.Widgets },
                icon = { Icon(Icons.Filled.Widgets, contentDescription = null) },
                label = { Text("Widgets") },
            )
            item(
                selected = current == Destination.Settings,
                onClick = { current = Destination.Settings },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text("Settings") },
            )
        },
    ) {
        // Edge-to-edge: the outer Box fills the entire window (background
        // paints behind the status bar / nav bar), and each destination
        // gets the safe-drawing insets as `contentPadding`. Scrollable
        // tabs apply it to their LazyColumn so list items scroll under
        // the bars; static tabs pad their root container so chrome
        // doesn't get clipped.
        val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
        Box(Modifier.fillMaxSize()) {
            when (current) {
                Destination.Dashboards -> DashboardsTab(session, safeInsets)
                Destination.Widgets -> WidgetsScreen(safeInsets)
                Destination.Settings -> SettingsScreen(
                    session = session,
                    onToggleDemo = onToggleDemo,
                    onSignOut = onSignOut,
                    contentPadding = safeInsets,
                )
            }
        }
    }
}

/**
 * Two-step stack inside the Dashboards tab: picker → view. Kept local
 * here rather than wiring in Nav3's NavDisplay for this shallow flow —
 * when the app gets deeper navigation (per-card detail, widget
 * configure) we promote to a real `NavDisplay`.
 */
@Composable
private fun DashboardsTab(session: HaSession, contentPadding: PaddingValues) {
    var opened by rememberSaveable { mutableStateOf<String?>(DASHBOARD_UNSET) }
    var installPending by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
    val openedValue = opened

    if (openedValue == DASHBOARD_UNSET) {
        DashboardPickerScreen(
            session = session,
            onDashboardPicked = { urlPath -> opened = urlPath },
            contentPadding = contentPadding,
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
            contentPadding = contentPadding,
        )
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

@Composable
private fun SettingsScreen(
    session: HaSession,
    onToggleDemo: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val isDemo = session is DemoHaSession
    val graph = LocalTerrazzoGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widgetRefresh = remember(context) { WidgetRefreshScheduler(context.applicationContext) }
    val themePref by graph.preferencesStore.themeStyle.collectAsState(initial = ThemePref.TerrazzoHome)
    val darkPref by graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

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

        OutlinedButton(onClick = onSignOut) {
            Text(if (isDemo) "Exit demo" else "Sign out")
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
