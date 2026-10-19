package ee.schimke.terrazzo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignTags
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.auth.rememberLoginController
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.LauncherColoursPref
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaReauthRequiredException
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.ReconnectBackoff
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.dashboard.CardActionsSheet
import ee.schimke.terrazzo.dashboard.CardDesignSources
import ee.schimke.terrazzo.dashboard.CardDesignSync
import ee.schimke.terrazzo.dashboard.CardHistoryScreen
import ee.schimke.terrazzo.dashboard.DashboardAgentScreen
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardPickerScreen
import ee.schimke.terrazzo.dashboard.DashboardSelectionScreen
import ee.schimke.terrazzo.dashboard.DashboardSwitcher
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.dashboard.ManagePinnedScreen
import ee.schimke.terrazzo.dashboard.StudioCardPreview
import ee.schimke.terrazzo.dashboard.TopBarOverflowMenu
import ee.schimke.terrazzo.dashboard.historyTitle
import ee.schimke.terrazzo.dashboard.rememberDashboardListState
import ee.schimke.terrazzo.dashboard.rememberSelectedDashboardListState
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.shared.AppScreen
import ee.schimke.terrazzo.shared.CardStudioScreen
import ee.schimke.terrazzo.shared.ReportFeedbackButton
import ee.schimke.terrazzo.shared.TerrazzoNavigation
import ee.schimke.terrazzo.ui.toUberTheme
import ee.schimke.terrazzo.wearsync.WearWidgetsScreen
import ee.schimke.terrazzo.widget.WidgetInstallSheet
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import ee.schimke.terrazzo.widget.WidgetsScreen
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Root composable. Two phases:
 * 1. **Unauthenticated**: discovery + login flow (one screen, no chrome). Completing login — or
 *    enabling demo mode — promotes to phase 2.
 * 2. **Authenticated**: a dashboard-first shell. The dashboard fills the screen; a small
 *    `TopAppBar` carries a [DashboardSwitcher] (current dashboard name + chevron + 1-tap-switch
 *    dropdown) and an overflow menu hosting Settings / Manage widgets / Sign out. No more
 *    `NavigationSuiteScaffold` — there's nothing to navigate to other than dashboards, and Settings
 *    / widgets are rare visits hidden behind one tap of the overflow.
 *
 * Demo mode (toggled from Settings, or from the Discovery "try demo" button) swaps the live
 * [HaSession] for a deterministic fake so the app can be explored without a working HA instance.
 */
@Composable
fun TerrazzoApp(initialDashboard: String? = null, initialSession: HaSession? = null) {
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

  // Sign out of [signedOut]'s instance: wipe its cached dashboards / snapshots and refresh-token
  // vault entry so the next user on this device can't read the previous account's data, and so
  // cold start goes back to discovery. [reason] is shown on the sign-in screen.
  fun signOut(signedOut: HaSession?, reason: Throwable? = null) {
    val previousBaseUrl = signedOut?.baseUrl
    scope.launch {
      graph.preferencesStore.setDemoMode(false)
      graph.preferencesStore.clearLastViewedDashboard()
      graph.preferencesStore.clearSelectedDashboardUrls()
      if (previousBaseUrl != null) {
        graph.offlineCache.clearInstance(previousBaseUrl)
        runCatching { graph.tokenVault.clear(previousBaseUrl) }
        graph.remoteUrlStore.clear(previousBaseUrl)
      }
      signedOut?.close()
    }
    widgetScheduler.cancelDemo()
    if (session === signedOut) session = null
    lastError = reason
  }

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
              session =
                graph.sessionFactory.create(baseUrl, access, tokens.expiresInSeconds?.seconds)
              previous?.close()
            }
            tokens.refreshToken?.let { graph.tokenVault.put(baseUrl, it) }
          }
          .onFailure { error ->
            // HA refused the stored refresh token: no retry can recover, so send the user back
            // to sign in rather than leave them on a cached dashboard that never connects.
            if (error is HaReauthRequiredException && session === initialSession) {
              signOut(initialSession, SignedOutException())
            }
          }
      }
    }
  }

  // A live session whose refresh token HA refuses mid-flight (revoked, user deactivated) can't
  // reconnect; sign out so the user can sign in again instead of retrying forever.
  LaunchedEffect(session) {
    val s = session ?: return@LaunchedEffect
    s.needsReauth.first { it }
    signOut(s, SignedOutException())
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
  // A live session re-mints its access token as connects need one, so re-read it on each
  // connection change rather than keeping the bearer it started with.
  LaunchedEffect(imageStack, session) {
    val s = session
    if (s == null) {
      imageStack?.setAuth(null, null)
      return@LaunchedEffect
    }
    s.connectionStatus.collect { imageStack?.setAuth(s.baseUrl, s.accessToken) }
  }

  val login =
    rememberLoginController(
      onReady = { baseUrl, accessToken ->
        session = graph.sessionFactory.create(baseUrl, accessToken)
      },
      onError = { lastError = it },
    )
  val connectionPolicy = graph.lanConnectionPolicy

  when (val s = session) {
    null ->
      UnauthenticatedScreen(
        onInstancePicked = { baseUrl ->
          when (val verdict = connectionPolicy.check(baseUrl)) {
            LanConnectionPolicy.Verdict.Allow -> login.start(baseUrl)
            is LanConnectionPolicy.Verdict.Deny -> lastError = IllegalStateException(verdict.reason)
          }
        },
        onDemoSelected = {
          scope.launch { graph.preferencesStore.setDemoMode(true) }
          session = graph.sessionFactory.createDemo()
          widgetScheduler.scheduleDemo()
        },
        error = lastError,
      )
    else ->
      AuthenticatedShell(
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
        onSignOut = { signOut(session) },
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

/**
 * How the user arrived on [AppScreen.ChooseDashboards]. The two paths differ in two ways:
 * - **Back / confirm destination** — signin-gate confirm lands on Dashboards; settings re-edit
 *   lands on Settings.
 * - **Back affordance** — signin gate has no back (the user MUST pick before getting to a
 *   dashboard); settings entry has a back arrow that returns to Settings.
 */
private enum class SelectionEntry {
  Signin,
  Settings,
}

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
    selectionMissingAtSignin = !isDemo && graph.preferencesStore.selectedDashboardUrlsNow() == null
    selectionResolved = true
  }
  LaunchedEffect(selectionResolved, selectionMissingAtSignin) {
    if (selectionResolved && selectionMissingAtSignin && screen != AppScreen.ChooseDashboards) {
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
  val navigateBack: () -> Unit = {
    when {
      screen == AppScreen.ChooseDashboards && selectionEntry == SelectionEntry.Signin -> Unit
      screen == AppScreen.ChooseDashboards || screen == AppScreen.SyncDiagnostics ->
        screen = AppScreen.Settings
      else -> screen = AppScreen.Dashboards
    }
  }
  BackHandler(enabled = screen != AppScreen.Dashboards, onBack = navigateBack)

  TerrazzoNavigation(screen, onBack = navigateBack) { destination ->
    when (destination) {
      AppScreen.Dashboards ->
        DashboardsRoot(
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
          onOpenSyncDiagnostics =
            if (wearReady) {
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
      AppScreen.Widgets -> WidgetsScreen(onBack = { screen = AppScreen.Dashboards })
      AppScreen.Pinned -> ManagePinnedScreen(onBack = { screen = AppScreen.Dashboards })
      AppScreen.WearWidgets -> WearWidgetsScreen(onBack = { screen = AppScreen.Dashboards })
      AppScreen.SyncDiagnostics -> {
        val streaming by graph.wearSyncManager.streamActive.collectAsState()
        ee.schimke.terrazzo.wearsync.SyncDiagnosticsScreen(
          statsStore = app.syncStats,
          streamActive = streaming,
          onBack = { screen = AppScreen.Settings },
        )
      }
      AppScreen.Logs ->
        ee.schimke.terrazzo.logs.LogsScreen(onBack = { screen = AppScreen.Dashboards })
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
            title =
              when (selectionEntry) {
                SelectionEntry.Signin -> "Choose dashboards"
                SelectionEntry.Settings -> "Manage dashboards"
              },
          )
        } else {
          DashboardSelectionScreen(
            state = rawList,
            initialSelection = selectionInitial,
            onConfirm = { urls ->
              scope.launch { graph.preferencesStore.setSelectedDashboardUrls(urls) }
              // Avoid stranding the user on this screen
              // between the pref write and the snapshot
              // flag flipping.
              selectionMissingAtSignin = false
              screen =
                when (selectionEntry) {
                  SelectionEntry.Signin -> AppScreen.Dashboards
                  SelectionEntry.Settings -> AppScreen.Settings
                }
            },
            onBack =
              when (selectionEntry) {
                SelectionEntry.Signin -> null
                SelectionEntry.Settings -> {
                  { screen = AppScreen.Settings }
                }
              },
            title =
              when (selectionEntry) {
                SelectionEntry.Signin -> "Choose dashboards"
                SelectionEntry.Settings -> "Manage dashboards"
              },
          )
        }
      }
    }
  }
}

/**
 * The dashboard-first shell. Loads the dashboards list once for the session, hosts the picker →
 * view local nav, and renders the [DashboardTopBar] with the
 * [DashboardSwitcher] + [TopBarOverflowMenu].
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
  val wearWidgetsSupported by app.wearCapabilityProbe.stateFlow(scope).collectAsState()
  val logsViewEnabled by graph.preferencesStore.logsViewEnabled.collectAsState(initial = false)

  var opened by rememberSaveable { mutableStateOf(initialDashboardToOpened(initialDashboard)) }
  // Two-layer write-mode state:
  //   - `permanentWriteMode` is the persisted opt-in (Settings).
  //   - `sessionWriteMode` is a process-scoped override the chip writes
  //     to. Null means "no override yet" — fall back to the permanent
  //     setting, which on a fresh cold start is the read-only default.
  val permanentWrite by graph.preferencesStore.permanentWriteMode.collectAsState(initial = false)
  val sessionWrite by graph.sessionWriteMode.writeMode.collectAsState()
  val writeMode = sessionWrite ?: permanentWrite
  val readOnly = !writeMode
  var installPending by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  // Long-press opens a per-card history screen (card + history chart +
  // links). Holds the pressed card plus the snapshot it was pressed
  // against, so the preview at the top matches the dashboard frame.
  var historyTarget by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  // Long-press first offers a menu (CardActionsSheet): fork, home screen, or history.
  var actionsTarget by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  var studioTarget by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  // "Edit with an agent" for the opened dashboard, and a count of saves that re-keys it to reload.
  var agentOpen by remember { mutableStateOf(false) }
  var dashboardSaves by remember { mutableIntStateOf(0) }
  val studioServer by
    graph.preferencesStore.designServerUrl.collectAsState(initial = "https://ha.coo.ee/designs/v1")
  val openedValue = opened

  // System-back: history → dashboard, then view → picker; on the picker
  // the platform handles it. The history overlay takes precedence, so
  // the dashboard's view→picker handler stays disabled while it's up.
  BackHandler(
    enabled = historyTarget == null && studioTarget == null && openedValue != DASHBOARD_UNSET
  ) {
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

  // Tie the HA connection to the foreground. We hold the socket open
  // only while the activity is RESUMED:
  //
  //  - On entering the foreground, if we parked the socket on the way
  //    out (or a prior attempt failed), reconnect now so the user sees
  //    "Connecting" immediately instead of a stale status.
  //  - While foregrounded, watch for a Failed status and retry with an
  //    exponential backoff (2 s doubling to 5 min, reset on success).
  //    Each retry lands at Connected (loop idles) or at Failed
  //    (StateFlow re-emits and we retry again). A session whose
  //    credentials HA refused stops retrying; TerrazzoApp signs out.
  //  - On leaving the foreground, cleanly disconnect. Without this the
  //    OS tears the idle socket down in the background and the next
  //    read-loop failure logs a spurious Error; a clean disconnect logs
  //    a neutral "Disconnected" instead.
  LaunchedEffect(session, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      val backoff = ReconnectBackoff()
      val status = session.connectionStatus.value
      if (
        status == SessionConnectionStatus.Disconnected || status == SessionConnectionStatus.Failed
      ) {
        runCatching { session.connect() }
      }
      try {
        while (true) {
          session.connectionStatus.first { it == SessionConnectionStatus.Failed }
          if (session.needsReauth.value) awaitCancellation()
          delay(backoff.next())
          if (session.connectionStatus.value == SessionConnectionStatus.Failed) {
            runCatching { session.connect() }
          }
          if (session.connectionStatus.value == SessionConnectionStatus.Connected) backoff.reset()
        }
      } finally {
        // repeatOnLifecycle has cancelled us; close the socket
        // outside the cancelled scope so the suspend call runs.
        withContext(NonCancellable) { runCatching { session.disconnect() } }
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
      logStore.recordConnection(status = status.toLogStatus(), message = session.baseUrl)
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

  val historyOverlay = historyTarget
  val studioOverlay = studioTarget
  if (agentOpen && openedValue != DASHBOARD_UNSET) {
    BackHandler { agentOpen = false }
    DashboardAgentScreen(
      session = session,
      urlPath = openedValue,
      title = readyDashboards.firstOrNull { it.urlPath == openedValue }?.title,
      onBack = { agentOpen = false },
      onSaved = { dashboardSaves++ },
    )
  } else if (studioOverlay != null) {
    val (card, snapshot) = studioOverlay
    BackHandler { studioTarget = null }
    CardStudioScreen(
      card,
      snapshot,
      onBack = { studioTarget = null },
      initialServerUrl = studioServer.ifBlank { "https://ha.coo.ee" },
      refreshSnapshot = {
        val current = session.loadDashboard(openedValue.takeIf { it != DASHBOARD_UNSET }).second
        studioTarget = card to current
        current
      },
    ) {
      StudioCardPreview(session, card, snapshot)
    }
  } else if (historyOverlay != null) {
    val (historyCard, historySnapshot) = historyOverlay
    BackHandler { historyTarget = null }
    CardHistoryScreen(
      session = session,
      card = historyCard,
      snapshot = historySnapshot,
      onBack = { historyTarget = null },
      onAddToHomeScreen = { installPending = historyCard to historySnapshot },
      onDesignCard = { studioTarget = historyCard to historySnapshot },
    )
  } else {
    Scaffold(
      topBar = {
        DashboardTopBar(
          title = {
            if (openedValue == DASHBOARD_UNSET) {
              Text("Pick a dashboard", maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
              DashboardSwitcher(
                dashboards = readyDashboards,
                currentUrlPath = openedValue,
                onSwitch = { newPath ->
                  opened = newPath
                  scope.launch { graph.preferencesStore.setLastViewedDashboard(newPath) }
                },
              )
            }
          },
          showAgentButton = openedValue != DASHBOARD_UNSET && !readOnly,
          onOpenAgent = { agentOpen = true },
          notifications = notifications,
          onDismissNotification = { n ->
            scope.launch { runCatching { session.dismissNotification(n.notificationId) } }
          },
          onClearNotifications = {
            scope.launch { runCatching { session.dismissAllNotifications() } }
          },
          connectionStatus = connectionStatus,
          onRetryConnection = onRetryConnection,
          writeMode = writeMode,
          onWriteModeChange = { graph.sessionWriteMode.set(it) },
          overflowMenu = { agentInOverflow ->
            TopBarOverflowMenu(
              onOpenSettings = onOpenSettings,
              onOpenWidgets = onOpenWidgets,
              onOpenPinned = onOpenPinned,
              onOpenWearWidgets = if (wearWidgetsSupported) onOpenWearWidgets else null,
              onOpenLogs = if (logsViewEnabled) onOpenLogs else null,
              onSignOut = onSignOut,
              onOpenAgent = agentInOverflow,
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
        // Key on the opened dashboard so switching dashboards
        // disposes the previous one's whole render subtree —
        // crucially the per-card `AndroidView` players — instead of
        // reusing their composition slots. The players are hosted via
        // `AndroidView(factory = { view })`, whose factory runs once
        // per node; without a fresh node on switch, a reused slot
        // keeps showing the previous dashboard's view until a
        // collapse/expand forces recreation. Re-seeding from the
        // offline cache makes the new dashboard paint immediately.
        key(openedValue, dashboardSaves) {
          DashboardViewScreen(
            session = session,
            urlPath = openedValue,
            readOnly = readOnly,
            // Long-press opens the card's actions, handed the live
            // snapshot the card was pressed against so a fork or the
            // history preview matches the dashboard frame.
            onCardLongPress = { card, snapshot -> actionsTarget = card to snapshot },
            contentPadding = padding,
          )
        }
      }
    }
  }

  actionsTarget?.let { target ->
    CardActionsSheet(
      title = target.first.historyTitle(),
      onFork = { studioTarget = target },
      onAddToHomeScreen = { installPending = target },
      onShowDetails = { historyTarget = target },
      onDismiss = { actionsTarget = null },
    )
  }

  val monitorContext = LocalContext.current
  installPending?.let { (card, snapshot) ->
    WidgetInstallSheet(
      baseUrl = session.baseUrl,
      dashboardUrlPath = openedValue.takeIf { it != DASHBOARD_UNSET } ?: "",
      card = card,
      snapshot = snapshot,
      onDismiss = { installPending = null },
      refreshSnapshot = {
        session.loadDashboard(openedValue.takeIf { it != DASHBOARD_UNSET }).second
      },
      // Only offer monitoring in demo mode — the service pulls
      // state from DemoData today. Live mode needs a shared
      // non-UI HA session; landing in a follow-up.
      onMonitor =
        if (session is DemoHaSession && graph.cardMonitor.isEnabled) {
          { graph.cardMonitor.start(card) }
        } else null,
    )
  }
}

/** Sentinel for "no dashboard opened yet". null is a valid urlPath (the default dashboard). */
private const val DASHBOARD_UNSET = "__none__"

/** Home Assistant refused the stored refresh token; shown on the sign-in screen. */
private class SignedOutException :
  IllegalStateException("Home Assistant signed this device out. Sign in again.")

enum class ConnectionStatus(val label: String) {
  Failed("Failed"),
  Connecting("Connecting"),
  Connected("Connected"),
  Disconnected("Paused"),
}

private fun SessionConnectionStatus.toUiConnectionStatus(): ConnectionStatus =
  when (this) {
    SessionConnectionStatus.Failed -> ConnectionStatus.Failed
    SessionConnectionStatus.Connecting -> ConnectionStatus.Connecting
    SessionConnectionStatus.Connected -> ConnectionStatus.Connected
    SessionConnectionStatus.Disconnected -> ConnectionStatus.Disconnected
  }

private fun SessionConnectionStatus.toLogStatus():
  ee.schimke.terrazzo.core.logs.LogConnectionStatus =
  when (this) {
    SessionConnectionStatus.Failed -> ee.schimke.terrazzo.core.logs.LogConnectionStatus.Error
    SessionConnectionStatus.Connecting ->
      ee.schimke.terrazzo.core.logs.LogConnectionStatus.Connecting
    SessionConnectionStatus.Connected -> ee.schimke.terrazzo.core.logs.LogConnectionStatus.Connected
    SessionConnectionStatus.Disconnected ->
      ee.schimke.terrazzo.core.logs.LogConnectionStatus.Disconnected
  }

/**
 * Translate the persisted last-viewed-dashboard pref into the local `opened` sentinel/urlPath
 * encoding `DashboardsRoot` uses:
 * - `null` (pref absent) → `null` (HA's default dashboard). First launch lands on the default
 *   dashboard rather than the picker; the picker stays reachable via system-back from a view.
 * - [PreferencesStore.DEFAULT_DASHBOARD_SENTINEL] → `null` (HA's default dashboard, whose `urlPath`
 *   is null over the wire).
 * - any other string — that `urlPath`.
 */
private fun initialDashboardToOpened(stored: String?): String? =
  when (stored) {
    null,
    PreferencesStore.DEFAULT_DASHBOARD_SENTINEL -> null
    else -> stored
  }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
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
  val themePref by
    graph.preferencesStore.themeStyle.collectAsState(initial = ThemePref.TerrazzoHome)
  val darkPref by graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)
  val cardStylePref by
    graph.preferencesStore.cardStyle.collectAsState(initial = CardStylePref.Classic)
  val launcherColours by
    graph.preferencesStore.launcherColours.collectAsState(initial = LauncherColoursPref.System)
  val gridLayoutEnabled by
    graph.preferencesStore.experimentalGridLayout.collectAsState(initial = false)
  val collapsedModeEnabled by graph.preferencesStore.collapsedMode.collectAsState(initial = true)
  val logsViewEnabled by graph.preferencesStore.logsViewEnabled.collectAsState(initial = false)
  val flashOnDataChange by graph.preferencesStore.flashOnDataChange.collectAsState(initial = false)
  val dataGridOverlay by graph.preferencesStore.dataGridOverlay.collectAsState(initial = false)
  val hapticsEnabled by graph.preferencesStore.haptics.collectAsState(initial = true)
  val soundsEnabled by graph.preferencesStore.interfaceSounds.collectAsState(initial = false)
  val designServerUrl by graph.preferencesStore.designServerUrl.collectAsState(initial = "")
  val designVariant by
    graph.preferencesStore.designVariant.collectAsState(
      initial = PreferencesStore.DEFAULT_DESIGN_VARIANT
    )
  val designAuthor by graph.preferencesStore.designAuthor.collectAsState(initial = "")
  val designToken by graph.preferencesStore.designToken.collectAsState(initial = "")
  val permanentWriteEnabled by
    graph.preferencesStore.permanentWriteMode.collectAsState(initial = false)

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
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(padding)
          .consumeWindowInsets(padding)
          .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ReportFeedbackButton(version = BuildConfig.VERSION_NAME)
      Text("Connected to", style = MaterialTheme.typography.labelMedium)
      Text(
        if (isDemo) "Demo mode — offline fake data" else session.baseUrl,
        style = MaterialTheme.typography.bodyMedium,
      )
      if (!isDemo) {
        val externalUrl by
          graph.remoteUrlStore.externalUrl(session.baseUrl).collectAsState(initial = null)
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
        Switch(checked = isDemo, onCheckedChange = onToggleDemo)
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

      CardStyleSection(
        selected = cardStylePref,
        onSelect = { pref ->
          scope.launch {
            graph.preferencesStore.setCardStyle(pref)
            widgetRefresh.refreshAllNow()
          }
        },
      )

      LauncherColoursSection(
        selected = launcherColours,
        onSelect = { pref ->
          scope.launch {
            graph.preferencesStore.setLauncherColours(pref)
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
          Text("Haptics", style = MaterialTheme.typography.titleMedium)
          Text(
            "A tick when a card switches something on or off, a tap per keypad " +
              "key, and a buzz when an action is dropped or fails.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Switch(
          checked = hapticsEnabled,
          onCheckedChange = { enabled ->
            scope.launch { graph.preferencesStore.setHaptics(enabled) }
          },
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Interface sounds", style = MaterialTheme.typography.titleMedium)
          Text(
            "Quiet sounds for the same moments, voiced to the card style: a " +
              "mechanical switch for Console, a beep for LCD and Trinitron, a soft " +
              "tick otherwise. Silent while the ringer is on vibrate or silent.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Switch(
          checked = soundsEnabled,
          onCheckedChange = { enabled ->
            scope.launch { graph.preferencesStore.setInterfaceSounds(enabled) }
          },
        )
      }

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
          Text("Debug: flash cards on data change", style = MaterialTheme.typography.titleMedium)
          Text(
            "Briefly highlight a card whenever the entity state it renders " +
              "changes. Handy with demo mode to see which cards are live. " +
              "Visual only — nothing about the rendered card changes.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Switch(
          checked = flashOnDataChange,
          onCheckedChange = { enabled ->
            scope.launch { graph.preferencesStore.setFlashOnDataChange(enabled) }
          },
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Debug: data grid overlay", style = MaterialTheme.typography.titleMedium)
          Text(
            "Float a grid over the dashboard listing every entity its cards " +
              "use — the key, current value, and how long ago it last changed. " +
              "Tap ✕ on the panel to dismiss.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Switch(
          checked = dataGridOverlay,
          onCheckedChange = { enabled ->
            scope.launch { graph.preferencesStore.setDataGridOverlay(enabled) }
          },
        )
      }

      // Saving a setting changes the design choice, which syncs the designs it needs (see
      // TerrazzoApplication).
      DesignServerSetting(
        url = designServerUrl,
        variant = designVariant,
        author = designAuthor,
        token = designToken,
        onUrlChange = { url -> scope.launch { graph.preferencesStore.setDesignServerUrl(url) } },
        onVariantChange = { scope.launch { graph.preferencesStore.setDesignVariant(it) } },
        onAuthorChange = { scope.launch { graph.preferencesStore.setDesignAuthor(it) } },
        onTokenChange = { scope.launch { graph.preferencesStore.setDesignToken(it) } },
      )

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

      OutlinedButton(onClick = onSignOut) { Text(if (isDemo) "Exit demo" else "Sign out") }

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
      ThemeRow(pref = pref, selected = pref == selected, onClick = { onSelectTheme(pref) })
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
              }
            )
          },
        )
      }
    }
  }
}

/**
 * The card style: how dashboard cards are drawn. Classic keeps the cards' own look in the chosen
 * palette; each uber theme brings its own palette, shapes, type and instrument designs (an LCD's
 * segment bars, a Trinitron VU needle). Dashboard cards and launcher widgets: widgets take the
 * style on the launcher's colours unless [LauncherColoursSection] says otherwise. The app's own
 * screens keep the palette above.
 */
@Composable
private fun CardStyleSection(selected: CardStylePref, onSelect: (CardStylePref) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Card style", style = MaterialTheme.typography.titleMedium)
    Text(
      "How dashboard cards are drawn. Classic follows the theme above; the others are complete " +
        "looks with their own colours, shapes and gauges.",
      style = MaterialTheme.typography.bodySmall,
    )
    CardStylePref.entries.forEach { pref ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        RadioButton(selected = pref == selected, onClick = { onSelect(pref) })
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(pref.toUberTheme().displayName, style = MaterialTheme.typography.bodyLarge)
          Text(cardStyleTagline(pref), style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
}

/**
 * Whether launcher widgets keep the launcher's colours (the default) or draw the card style with
 * its palette, as the dashboard does.
 */
@Composable
private fun LauncherColoursSection(
  selected: LauncherColoursPref,
  onSelect: (LauncherColoursPref) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Launcher widget colours", style = MaterialTheme.typography.titleMedium)
    LauncherColoursPref.entries.forEach { pref ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        RadioButton(selected = pref == selected, onClick = { onSelect(pref) })
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            when (pref) {
              LauncherColoursPref.System -> "System colours"
              LauncherColoursPref.App -> "Card style colours"
            },
            style = MaterialTheme.typography.bodyLarge,
          )
          Text(
            when (pref) {
              LauncherColoursPref.System ->
                "Your launcher's colours, with the card style's gauges, type and shapes"
              LauncherColoursPref.App -> "The card style's own colours, as on your dashboard"
            },
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

private fun cardStyleTagline(pref: CardStylePref): String =
  when (pref) {
    CardStylePref.Classic -> "The cards' own look, in your theme's palette"
    CardStylePref.Expressive -> "Material 3 Expressive · big shapes, capsule meters"
    CardStylePref.Trinitron -> "Graphite hi-fi · VU needles, LED level meters"
    CardStylePref.Lcd -> "Segment LCD · bar graphs with ghost segments"
    CardStylePref.Braun -> "Dieter Rams · dials and tuning scales"
    CardStylePref.Nixie -> "Glowing tubes · black glass, orange filaments"
    CardStylePref.Ikea -> "IKEA showroom · birch shelves, tactile smart-light controls"
    CardStylePref.Console -> "Skeuomorphic studio desk · VU meters, faders, knobs"
    CardStylePref.Mushroom,
    CardStylePref.Bubble,
    CardStylePref.Minimalist,
    CardStylePref.Cupertino,
    CardStylePref.Catppuccin,
    CardStylePref.Obsidian,
    CardStylePref.Noctis,
    CardStylePref.Lcars,
    CardStylePref.Arctic,
    CardStylePref.Aeon -> pref.toUberTheme().description
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

private fun themePrefDisplayName(pref: ThemePref): String =
  when (pref) {
    ThemePref.Material3 -> "Material 3"
    ThemePref.TerrazzoHome -> "Home"
    ThemePref.TerrazzoMushroom -> "Mushroom"
    ThemePref.TerrazzoMinimalist -> "Minimalist"
    ThemePref.TerrazzoKiosk -> "Kiosk"
  }

private fun themePrefTagline(pref: ThemePref): String =
  when (pref) {
    ThemePref.Material3 -> "Defaults · dynamic colour on Android 12+"
    ThemePref.TerrazzoHome -> "Home Assistant blue · Roboto Flex + Inter"
    ThemePref.TerrazzoMushroom -> "Warm salmon · Figtree"
    ThemePref.TerrazzoMinimalist -> "Neutral slate · IBM Plex Sans"
    ThemePref.TerrazzoKiosk -> "High-contrast teal · Atkinson Hyperlegible"
  }

/**
 * Experimental: base URL of a card design server. Empty (the default) keeps the feature off. Saved
 * when the field loses focus or the keyboard's Done is pressed, not on every keystroke, so a
 * half-typed URL never triggers a fetch.
 */
@Composable
private fun DesignServerSetting(
  url: String,
  variant: String,
  author: String,
  token: String,
  onUrlChange: (String) -> Unit,
  onVariantChange: (String) -> Unit,
  onAuthorChange: (String) -> Unit,
  onTokenChange: (String) -> Unit,
) {
  val context = LocalContext.current
  var draft by remember(url) { mutableStateOf(url) }
  val revision by CardDesignSync.revision.collectAsState()
  val tags by
    produceState<DesignTags?>(initialValue = null, url, revision) {
      value = CardDesignSync.tags(context, url)
    }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Card design server", style = MaterialTheme.typography.titleMedium)
    Text(
      "Card designs come from this server (manifest.json + blobs/), chosen by your palette, " +
        "card style, variant and author; the app's own designs cover anything it lacks. The " +
        "manifest is checked nightly while charging. Leave empty to use only the app's designs.",
      style = MaterialTheme.typography.bodySmall,
    )
    androidx.compose.material3.OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      placeholder = { Text("Off") },
      keyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions(
          imeAction = androidx.compose.ui.text.input.ImeAction.Done
        ),
      keyboardActions =
        androidx.compose.foundation.text.KeyboardActions(onDone = { onUrlChange(draft) }),
      modifier =
        Modifier.fillMaxWidth().onFocusChanged {
          if (!it.isFocused && draft != url) onUrlChange(draft)
        },
    )
    if (url.isNotBlank()) {
      DesignTokenField(url, token, onTokenChange)
      DesignSourcesSummary()
    }
    tags?.let { tags ->
      DesignTagPicker(
        title = "Variant",
        // The chosen variant stays listed even when this server has none of it yet.
        options = (tags.variant.keys + variant).distinct().map { it to it },
        selected = variant,
        onSelect = onVariantChange,
      )
      DesignTagPicker(
        title = "Author",
        options =
          listOf("" to "Built-in") +
            (tags.author.keys - DesignEntry.BUILTIN_AUTHOR + author.ifBlank { null })
              .filterNotNull()
              .distinct()
              .map { it to it.removePrefix("github:") },
        selected = author,
        onSelect = onAuthorChange,
      )
    }
  }
}

/**
 * The design server's device token: with it, the manifest also lists the designs this person
 * published privately. Made on the server's `/my` page while signed in with GitHub.
 */
@Composable
private fun DesignTokenField(url: String, token: String, onTokenChange: (String) -> Unit) {
  var draft by remember(token) { mutableStateOf(token) }
  val myPage = url.trimEnd('/').removeSuffix("/designs/v1") + "/my"
  androidx.compose.material3.OutlinedTextField(
    value = draft,
    onValueChange = { draft = it },
    singleLine = true,
    label = { Text("Device token") },
    supportingText = { Text("Shows your private designs. Create one at $myPage") },
    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
    keyboardOptions =
      androidx.compose.foundation.text.KeyboardOptions(
        imeAction = androidx.compose.ui.text.input.ImeAction.Done
      ),
    keyboardActions =
      androidx.compose.foundation.text.KeyboardActions(onDone = { onTokenChange(draft) }),
    modifier =
      Modifier.fillMaxWidth().onFocusChanged {
        if (!it.isFocused && draft != token) onTokenChange(draft)
      },
  )
}

/**
 * Where this session's card designs came from, and the card types and shapes the server had no
 * design for: the gaps it should fill. Read once when Settings opens.
 */
@Composable
private fun DesignSourcesSummary() {
  // Only the current choice's record: until a dashboard loads a changed choice there is none.
  val prefs = LocalTerrazzoGraph.current.preferencesStore
  val context = LocalContext.current
  val choice by
    remember(prefs) { CardDesignSync.choice(context, prefs) }.collectAsState(initial = null)
  val summary = remember(choice) { CardDesignSources.summary(choice) }
  if (summary.server == 0 && summary.local.isEmpty()) return
  Text(
    "This session: ${summary.server} card designs from the server, " +
      "${summary.local.size} from the app.",
    style = MaterialTheme.typography.bodySmall,
  )
  summary.local.take(MAX_LISTED_GAPS).forEach { key ->
    Text(
      "· ${key.cardType}" + key.shape.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty(),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
  }
  if (summary.local.size > MAX_LISTED_GAPS) {
    Text(
      "and ${summary.local.size - MAX_LISTED_GAPS} more",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private const val MAX_LISTED_GAPS = 8

/** One design tag's values as chips, [selected] checked; each option is a value and its label. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DesignTagPicker(
  title: String,
  options: List<Pair<String, String>>,
  selected: String,
  onSelect: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      options.forEach { (value, label) ->
        FilterChip(
          selected = value == selected,
          onClick = { onSelect(value) },
          label = { Text(label) },
        )
      }
    }
  }
}
