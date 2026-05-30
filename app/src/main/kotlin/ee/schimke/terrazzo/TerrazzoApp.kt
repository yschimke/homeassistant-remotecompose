package ee.schimke.terrazzo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardSelectionScreen
import ee.schimke.terrazzo.dashboard.DashboardSwitcher
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.dashboard.ManagePinnedScreen
import ee.schimke.terrazzo.dashboard.TopBarOverflowMenu
import ee.schimke.terrazzo.dashboard.rememberDashboardListState
import ee.schimke.terrazzo.dashboard.rememberSelectedDashboardListState
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.wearsync.WearWidgetsScreen
import ee.schimke.terrazzo.notifications.NotificationBell
import ee.schimke.terrazzo.widget.WidgetInstallSheet
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import ee.schimke.terrazzo.widget.WidgetsScreen
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    val wearSync = graph.wearSyncManager

    // Persisted demo-mode flag survives process restarts, so a user who
    // opted into demo doesn't see the login screen again on relaunch.
    // Also re-arms the widget refresh chain in case the worker queue
    // was flushed (e.g. "Clear data" via system Settings). When a
    // cache-only [initialSession] was passed in (cold-start auto-resume
    // from a prior login), the demo path overrides it; otherwise we
    // mint a fresh access token from the vault's refresh token and
    // swap the stub for a real live session in the background.
    LaunchedEffect(Unit) {
        if (graph.preferencesStore.demoModeNow()) {
            session = graph.sessionFactory.createDemo()
            widgetScheduler.scheduleDemo()
        } else if (initialSession != null) {
            val baseUrl = initialSession.baseUrl
            val refreshToken = graph.tokenVault.get(baseUrl)
            if (refreshToken != null) {
                runCatching { graph.authService.refreshAccessToken(baseUrl, refreshToken) }
                    .onSuccess { tokens ->
                        val access = tokens.accessToken
                        // The user may have signed out or toggled demo
                        // in the time the refresh was in flight; only
                        // swap if we're still on the cache-only stub.
                        if (access != null && session === initialSession) {
                            val previous = session
                            session = graph.sessionFactory.create(baseUrl, access)
                            previous?.close()
                        }
                        tokens.refreshToken?.let { graph.tokenVault.put(baseUrl, it) }
                    }
            }
        }
    }

    // Mirror the active session to the wear data layer so the watch
    // can render dashboards / live values from whatever's current.
    LaunchedEffect(session) { wearSync.setSession(session) }

    // Once a live session has finished connecting, ask HA for its
    // config and stash the `external_url` so the OkHttp interceptor
    // can swap in the public host when LAN isn't reachable
    // (e.g. on cellular). A demo / offline session returns null
    // from [fetchInstanceConfig], so this no-ops there.
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        s.connectionStatus.first { it == SessionConnectionStatus.Connected }
        val config = runCatching { s.fetchInstanceConfig() }.getOrNull() ?: return@LaunchedEffect
        graph.remoteUrlStore.setExternalUrl(s.baseUrl, config.externalUrl)
    }

    // Plumb session credentials into the process-wide image stack so
    // the singleton OkHttp client adds `Authorization: Bearer …` to
    // same-host fetches without rebuilding the loader. Demo / null
    // sessions clear the bearer (`accessToken=null`) — the
    // interceptor then passes requests through unmodified.
    val imageStack = LocalHaImageStack.current
    LaunchedEffect(imageStack, session) {
        imageStack?.setAuth(session?.baseUrl, session?.accessToken)
    }

    val login = rememberLoginController(
        onReady = { baseUrl, accessToken ->
            session = graph.sessionFactory.create(baseUrl, accessToken)
        },
        onError = { lastError = it },
    )
    val connectionPolicy = graph.lanConnectionPolicy

    when (val s = session) {
        null -> UnauthenticatedScreen(
            onInstancePicked = { baseUrl ->
                when (val verdict = connectionPolicy.check(baseUrl)) {
                    LanConnectionPolicy.Verdict.Allow -> login.start(baseUrl)
                    is LanConnectionPolicy.Verdict.Deny ->
                        lastError = IllegalStateException(verdict.reason)
                }
            },
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
                    // Same reasoning for the dashboard selection set:
                    // the new identity may expose a different set of
                    // dashboards, so re-run selection rather than
                    // carry over the previous instance's choices.
                    graph.preferencesStore.clearSelectedDashboardUrls()
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
                    graph.preferencesStore.clearSelectedDashboardUrls()
                    // Wipe cached dashboards / snapshots and the
                    // refresh-token vault entry so the next user on
                    // this device can't read the previous account's
                    // data, and so cold start goes back to discovery.
                    if (previousBaseUrl != null) {
                        graph.offlineCache.clearInstance(previousBaseUrl)
                        runCatching { graph.tokenVault.clear(previousBaseUrl) }
                        graph.remoteUrlStore.clear(previousBaseUrl)
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
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        val message = error?.message ?: error?.javaClass?.simpleName
        if (!message.isNullOrBlank()) {
            snackbars.showSnackbar("Login failed: $message")
        }
    }

    DiscoveryScreen(
        onInstancePicked = onInstancePicked,
        onDemoSelected = onDemoSelected,
        snackbarHost = { SnackbarHost(hostState = snackbars) },
    )
}

private enum class AppScreen {
    Dashboards,
    Settings,
    Widgets,
    Pinned,
    WearWidgets,
    SyncDiagnostics,
    Logs,
    ChooseDashboards,
}

/**
 * How the user arrived on [AppScreen.ChooseDashboards]. The two paths
 * differ in two ways:
 *  - **Back / confirm destination** — signin-gate confirm lands on
 *    Dashboards; settings re-edit lands on Settings.
 *  - **Back affordance** — signin gate has no back (the user MUST
 *    pick before getting to a dashboard); settings entry has a back
 *    arrow that returns to Settings.
 */
private enum class SelectionEntry { Signin, Settings }

@Composable
private fun AuthenticatedShell(
    session: HaSession,
    initialDashboard: String?,
    onToggleDemo: (Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as TerrazzoApplication }
    val graph = LocalTerrazzoGraph.current
    val scope = rememberCoroutineScope()

    var screen by rememberSaveable { mutableStateOf(AppScreen.Dashboards) }
    var selectionEntry by rememberSaveable { mutableStateOf(SelectionEntry.Signin) }

    // Snapshot of the persisted selection at the moment the user
    // entered the ChooseDashboards screen. Captured here (rather than
    // observed via `collectAsState` inside the screen) because
    // DataStore reads are async: a Flow-based read would start as
    // `null`, the screen would default to "all checked", and the
    // saved subset would silently clobber the user's existing choice
    // when the real value arrived after composition.
    var selectionInitial by remember { mutableStateOf<Set<String>?>(null) }
    var selectionInitialResolved by remember { mutableStateOf(false) }

    // First-run gate: if the user hasn't been through the selection
    // screen for this session, force them onto it before opening any
    // dashboard. We snapshot the pref once per session (rather than
    // observing it as a Flow) so the screen doesn't auto-dismiss the
    // moment the confirm callback writes the pref. Demo mode skips
    // the gate entirely — `DemoData.BOARDS` is a curated showcase set
    // and there's no per-user "which of MY dashboards" question to
    // answer, so going straight to a dashboard matches both the
    // try-the-app intent and the instrumented test's expectations.
    var selectionResolved by remember(session) { mutableStateOf(false) }
    var selectionMissingAtSignin by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        val isDemo = session is DemoHaSession
        selectionMissingAtSignin =
            !isDemo && graph.preferencesStore.selectedDashboardUrlsNow() == null
        selectionResolved = true
    }
    LaunchedEffect(selectionResolved, selectionMissingAtSignin) {
        if (selectionResolved &&
            selectionMissingAtSignin &&
            screen != AppScreen.ChooseDashboards
        ) {
            selectionEntry = SelectionEntry.Signin
            // The gate fires precisely because no selection is
            // persisted, so the snapshot for the screen is `null`
            // (defaults to "all checked").
            selectionInitial = null
            selectionInitialResolved = true
            screen = AppScreen.ChooseDashboards
        }
    }

    // System-back routing. The signin-gate variant of ChooseDashboards
    // *swallows* back — the user has to pick at least one dashboard
    // before continuing — so the handler stays enabled and the
    // callback no-ops (a disabled `BackHandler` lets Android's
    // activity-level back through, which would exit the app). The
    // settings re-edit variant routes back to Settings. Other
    // non-Dashboards screens follow their existing rules. Inside
    // DashboardsRoot another BackHandler routes view → picker; the
    // platform handles back at the picker (exits app).
    BackHandler(enabled = screen != AppScreen.Dashboards) {
        when {
            screen == AppScreen.ChooseDashboards &&
                selectionEntry == SelectionEntry.Signin -> Unit
            screen == AppScreen.ChooseDashboards -> screen = AppScreen.Settings
            screen == AppScreen.SyncDiagnostics -> screen = AppScreen.Settings
            else -> screen = AppScreen.Dashboards
        }
    }

    when (screen) {
        AppScreen.Dashboards -> DashboardsRoot(
            session = session,
            initialDashboard = initialDashboard,
            onOpenSettings = { screen = AppScreen.Settings },
            onOpenWidgets = { screen = AppScreen.Widgets },
            onOpenPinned = { screen = AppScreen.Pinned },
            onOpenWearWidgets = { screen = AppScreen.WearWidgets },
            onOpenLogs = { screen = AppScreen.Logs },
            onSignOut = onSignOut,
        )
        AppScreen.Settings -> {
            val wearReady by graph.wearSyncManager.wearableAvailable.collectAsState()
            SettingsScreen(
                session = session,
                onToggleDemo = onToggleDemo,
                onSignOut = onSignOut,
                onBack = { screen = AppScreen.Dashboards },
                onOpenSyncDiagnostics = if (wearReady) {
                    { screen = AppScreen.SyncDiagnostics }
                } else null,
                onManageDashboards = {
                    selectionEntry = SelectionEntry.Settings
                    selectionInitialResolved = false
                    scope.launch {
                        selectionInitial = graph.preferencesStore.selectedDashboardUrlsNow()
                        selectionInitialResolved = true
                    }
                    screen = AppScreen.ChooseDashboards
                },
            )
        }
        AppScreen.Widgets -> WidgetsScreen(
            onBack = { screen = AppScreen.Dashboards },
        )
        AppScreen.Pinned -> ManagePinnedScreen(
            onBack = { screen = AppScreen.Dashboards },
        )
        AppScreen.WearWidgets -> WearWidgetsScreen(
            onBack = { screen = AppScreen.Dashboards },
        )
        AppScreen.SyncDiagnostics -> {
            val streaming by graph.wearSyncManager.streamActive.collectAsState()
            ee.schimke.terrazzo.wearsync.SyncDiagnosticsScreen(
                statsStore = app.syncStats,
                streamActive = streaming,
                onBack = { screen = AppScreen.Settings },
            )
        }
        AppScreen.Logs -> ee.schimke.terrazzo.logs.LogsScreen(
            onBack = { screen = AppScreen.Dashboards },
        )
        AppScreen.ChooseDashboards -> {
            val rawList by rememberDashboardListState(session)
            // Render the screen only once we have a snapshot of the
            // persisted selection in hand. Without the gate, the
            // settings re-edit path would briefly mount with
            // `initialSelection = null` (defaulting to "all checked")
            // and the user's saved subset would only arrive after the
            // screen's internal `selected` state was already seeded.
            if (!selectionInitialResolved) {
                DashboardSelectionScreen(
                    state = DashboardListState.Loading,
                    initialSelection = null,
                    onConfirm = {},
                    onBack = null,
                    title = when (selectionEntry) {
                        SelectionEntry.Signin -> "Choose dashboards"
                        SelectionEntry.Settings -> "Manage dashboards"
                    },
                )
            } else {
                DashboardSelectionScreen(
                    state = rawList,
                    initialSelection = selectionInitial,
                    onConfirm = { urls ->
                        scope.launch {
                            graph.preferencesStore.setSelectedDashboardUrls(urls)
                        }
                        // Avoid stranding the user on this screen
                        // between the pref write and the snapshot
                        // flag flipping.
                        selectionMissingAtSignin = false
                        screen = when (selectionEntry) {
                            SelectionEntry.Signin -> AppScreen.Dashboards
                            SelectionEntry.Settings -> AppScreen.Settings
                        }
                    },
                    onBack = when (selectionEntry) {
                        SelectionEntry.Signin -> null
                        SelectionEntry.Settings -> { { screen = AppScreen.Settings } }
                    },
                    title = when (selectionEntry) {
                        SelectionEntry.Signin -> "Choose dashboards"
                        SelectionEntry.Settings -> "Manage dashboards"
                    },
                )
            }
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
    onOpenPinned: () -> Unit,
    onOpenWearWidgets: () -> Unit,
    onOpenLogs: () -> Unit,
    onSignOut: () -> Unit,
) {
    val graph = LocalTerrazzoGraph.current
    val scope = rememberCoroutineScope()
    val dashboards by rememberSelectedDashboardListState(session)
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as TerrazzoApplication }
    val wearWidgetsSupported by app.wearCapabilityProbe
        .stateFlow(scope)
        .collectAsState()
    val logsViewEnabled by graph.preferencesStore.logsViewEnabled.collectAsState(initial = false)

    var opened by rememberSaveable {
        mutableStateOf(initialDashboardToOpened(initialDashboard))
    }
    // Two-layer write-mode state:
    //   - `permanentWriteMode` is the persisted opt-in (Settings).
    //   - `sessionWriteMode` is a process-scoped override the chip writes
    //     to. Null means "no override yet" — fall back to the permanent
    //     setting, which on a fresh cold start is the read-only default.
    val permanentWrite by graph.preferencesStore.permanentWriteMode
        .collectAsState(initial = false)
    val sessionWrite by graph.sessionWriteMode.writeMode.collectAsState()
    val writeMode = sessionWrite ?: permanentWrite
    val readOnly = !writeMode
    var installPending by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
    val openedValue = opened

    // System-back: view → picker; on the picker the platform handles it.
    BackHandler(enabled = openedValue != DASHBOARD_UNSET) {
        opened = DASHBOARD_UNSET
    }

    val readyDashboards = (dashboards as? DashboardListState.Ready)?.dashboards.orEmpty()
    val sessionConnection by session.connectionStatus.collectAsState()
    val connectionStatus = sessionConnection.toUiConnectionStatus()
    val snackbars = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val notifications by session.notifications.collectAsState()

    // Snackbar pop on arrival: remember the ids we've already announced
    // so the next composition (or a refetch that returns the same list)
    // doesn't re-fire. The initial seed is what's already there when
    // the user opens the app — we treat those as already-seen.
    val seenIds = remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(notifications) {
        val previous = seenIds.value
        if (previous == null) {
            seenIds.value = notifications.map { it.notificationId }.toSet()
            return@LaunchedEffect
        }
        val current = notifications.map { it.notificationId }.toSet()
        val fresh = notifications.filter { it.notificationId !in previous }
        seenIds.value = current
        fresh.forEach { n ->
            val label = n.title?.takeIf { it.isNotBlank() } ?: n.notificationId
            snackbars.showSnackbar(label)
        }
    }

    // Keep the HA connection alive while the user has the app open.
    // While the activity is RESUMED, watch for a Failed status and
    // retry with a short backoff. Each retry either lands at Connected
    // (loop idles) or at Failed (StateFlow re-emits and we retry
    // again). When the activity is paused the [repeatOnLifecycle] scope
    // cancels, so we stop poking the network in the background.
    LaunchedEffect(session, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                session.connectionStatus.first { it == SessionConnectionStatus.Failed }
                delay(RECONNECT_BACKOFF_MS)
                if (session.connectionStatus.value == SessionConnectionStatus.Failed) {
                    runCatching { session.connect() }
                }
            }
        }
    }

    // Mirror live connection-status transitions into the debug log
    // buffer so the Logs view (when enabled) shows reconnect timing.
    // StateFlow already de-duplicates identical emissions, so each
    // entry here corresponds to a real transition.
    val logStore = graph.logStore
    LaunchedEffect(session, logStore) {
        session.connectionStatus.collect { status ->
            logStore.recordConnection(
                status = status.toLogStatus(),
                message = session.baseUrl,
            )
        }
    }

    val onRetryConnection: () -> Unit = {
        scope.launch {
            runCatching { session.connect() }
            if (session.connectionStatus.value != SessionConnectionStatus.Connected) {
                snackbars.showSnackbar("Couldn't connect to ${session.baseUrl}")
            }
        }
    }

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
                    NotificationBell(
                        notifications = notifications,
                        onDismiss = { n ->
                            scope.launch {
                                runCatching { session.dismissNotification(n.notificationId) }
                            }
                        },
                        onClearAll = {
                            scope.launch { runCatching { session.dismissAllNotifications() } }
                        },
                    )
                    Surface(
                        onClick = onRetryConnection,
                        color = connectionStatus.color.copy(alpha = 0.16f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            text = connectionStatus.label,
                            color = connectionStatus.color,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            text = if (readOnly) "Read" else "Write",
                            color = if (readOnly) READ_ONLY_COLOR else WRITE_COLOR,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Switch(
                            checked = writeMode,
                            onCheckedChange = { graph.sessionWriteMode.set(it) },
                        )
                    }
                    TopBarOverflowMenu(
                        onOpenSettings = onOpenSettings,
                        onOpenWidgets = onOpenWidgets,
                        onOpenPinned = onOpenPinned,
                        onOpenWearWidgets = if (wearWidgetsSupported) onOpenWearWidgets else null,
                        onOpenLogs = if (logsViewEnabled) onOpenLogs else null,
                        onSignOut = onSignOut,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbars) },
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
                readOnly = readOnly,
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
            dashboardUrlPath = openedValue.takeIf { it != DASHBOARD_UNSET } ?: "",
            card = card,
            snapshot = snapshot,
            onDismiss = { installPending = null },
            // Only offer monitoring in demo mode — the service pulls
            // state from DemoData today. Live mode needs a shared
            // non-UI HA session; landing in a follow-up.
            onMonitor = if (session is DemoHaSession && graph.cardMonitor.isEnabled) {
                { graph.cardMonitor.start(card) }
            } else null,
        )
    }
}

/** Sentinel for "no dashboard opened yet". null is a valid urlPath (the default dashboard). */
private const val DASHBOARD_UNSET = "__none__"

/**
 * Backoff between automatic reconnect attempts while the activity is
 * resumed. Short enough that a brief network blip recovers quickly,
 * long enough that we don't hammer a genuinely-down HA instance.
 */
private const val RECONNECT_BACKOFF_MS = 5_000L

enum class ConnectionStatus(val label: String, val color: Color) {
    Failed("Failed", Color(0xFFD32F2F)),
    Connecting("Connecting", Color(0xFF2E7D32)),
    Connected("Connected", Color(0xFF1976D2)),
}

private val READ_ONLY_COLOR = Color(0xFF455A64)
private val WRITE_COLOR = Color(0xFFE65100)

private fun SessionConnectionStatus.toUiConnectionStatus(): ConnectionStatus = when (this) {
    SessionConnectionStatus.Failed -> ConnectionStatus.Failed
    SessionConnectionStatus.Connecting -> ConnectionStatus.Connecting
    SessionConnectionStatus.Connected -> ConnectionStatus.Connected
}

private fun SessionConnectionStatus.toLogStatus(): ee.schimke.terrazzo.core.logs.LogConnectionStatus =
    when (this) {
        SessionConnectionStatus.Failed -> ee.schimke.terrazzo.core.logs.LogConnectionStatus.Error
        SessionConnectionStatus.Connecting -> ee.schimke.terrazzo.core.logs.LogConnectionStatus.Connecting
        SessionConnectionStatus.Connected -> ee.schimke.terrazzo.core.logs.LogConnectionStatus.Connected
    }

/**
 * Translate the persisted last-viewed-dashboard pref into the local
 * `opened` sentinel/urlPath encoding `DashboardsRoot` uses:
 *
 *   - `null` (pref absent) → `null` (HA's default dashboard). First
 *     launch lands on the default dashboard rather than the picker;
 *     the picker stays reachable via system-back from a view.
 *   - [PreferencesStore.DEFAULT_DASHBOARD_SENTINEL] → `null`
 *     (HA's default dashboard, whose `urlPath` is null over the wire).
 *   - any other string — that `urlPath`.
 */
private fun initialDashboardToOpened(stored: String?): String? = when (stored) {
    null, PreferencesStore.DEFAULT_DASHBOARD_SENTINEL -> null
    else -> stored
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    session: HaSession,
    onToggleDemo: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onOpenSyncDiagnostics: (() -> Unit)?,
    onManageDashboards: () -> Unit,
) {
    val isDemo = session is DemoHaSession
    val graph = LocalTerrazzoGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widgetRefresh = remember(context) { WidgetRefreshScheduler(context.applicationContext) }
    val themePref by graph.preferencesStore.themeStyle.collectAsState(initial = ThemePref.TerrazzoHome)
    val darkPref by graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)
    val gridLayoutEnabled by graph.preferencesStore.experimentalGridLayout.collectAsState(initial = false)
    val collapsedModeEnabled by graph.preferencesStore.collapsedMode.collectAsState(initial = true)
    val logsViewEnabled by graph.preferencesStore.logsViewEnabled.collectAsState(initial = false)
    val permanentWriteEnabled by graph.preferencesStore.permanentWriteMode.collectAsState(initial = false)

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
            if (!isDemo) {
                val externalUrl by graph.remoteUrlStore.externalUrl(session.baseUrl)
                    .collectAsState(initial = null)
                externalUrl?.let { url ->
                    Text("Public URL (away from home)", style = MaterialTheme.typography.labelMedium)
                    Text(url, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Populate the app with an interesting set of fake widgets that animate.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = isDemo,
                    onCheckedChange = onToggleDemo,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Manage dashboards", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pick which custom and built-in Home Assistant dashboards " +
                            "appear in the picker and the top-bar switcher.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onManageDashboards) { Text("Choose") }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Collapsed sections", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "On a long single-column dashboard, keep only the first section " +
                            "expanded and collapse the rest to their headings. Tap a heading " +
                            "to swap which section is open.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = collapsedModeEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { graph.preferencesStore.setCollapsedMode(enabled) }
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show logs view", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Exposes a debug screen via the overflow menu listing recent " +
                            "dashboard-entity updates, connect / disconnect events, and " +
                            "locally-dispatched service calls. In-memory only.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = logsViewEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { graph.preferencesStore.setLogsViewEnabled(enabled) }
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Permanent write mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "When off, every relaunch starts in Read so taps on the dashboard " +
                            "(and on home-screen widgets) never silently fire HA service " +
                            "calls. Turn on if you trust this device and prefer Write by " +
                            "default; the chip in the top bar still overrides per session.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = permanentWriteEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { graph.preferencesStore.setPermanentWriteMode(enabled) }
                    },
                )
            }

            OutlinedButton(onClick = onSignOut) {
                Text(if (isDemo) "Exit demo" else "Sign out")
            }

            // Sync diagnostics — buried at the bottom of Settings on
            // purpose. Shows DataItem write / MessageClient send counts
            // so power users can sanity-check wear-side chatter. Hidden
            // when the device has no usable Wearable API (no paired
            // watch / wearable component) so the link doesn't dangle.
            if (onOpenSyncDiagnostics != null) {
                androidx.compose.material3.TextButton(onClick = onOpenSyncDiagnostics) {
                    Text("Sync diagnostics")
                }
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
