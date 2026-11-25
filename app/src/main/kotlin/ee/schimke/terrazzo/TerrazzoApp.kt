package ee.schimke.terrazzo

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.design.cardFingerprint
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.auth.designTokenLogin
import ee.schimke.terrazzo.auth.rememberDesignSignIn
import ee.schimke.terrazzo.auth.rememberLoginController
import ee.schimke.terrazzo.core.auth.HaAuthService
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.ReconnectBackoff
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import ee.schimke.terrazzo.core.session.terrazzo
import ee.schimke.terrazzo.dashboard.CardActionsSheet
import ee.schimke.terrazzo.dashboard.CardDesignSync
import ee.schimke.terrazzo.dashboard.CardHistoryScreen
import ee.schimke.terrazzo.dashboard.CardStyleSheet
import ee.schimke.terrazzo.dashboard.DashboardEditorScreen
import ee.schimke.terrazzo.dashboard.DashboardListState
import ee.schimke.terrazzo.dashboard.DashboardSelectionScreen
import ee.schimke.terrazzo.dashboard.DashboardSwitcher
import ee.schimke.terrazzo.dashboard.DashboardViewScreen
import ee.schimke.terrazzo.dashboard.SkinInstallWorker
import ee.schimke.terrazzo.dashboard.Skins
import ee.schimke.terrazzo.dashboard.StudioCardPreview
import ee.schimke.terrazzo.dashboard.TopBarOverflowMenu
import ee.schimke.terrazzo.dashboard.preferredDashboardPath
import ee.schimke.terrazzo.dashboard.rememberDashboardListState
import ee.schimke.terrazzo.dashboard.rememberSelectedDashboardListState
import ee.schimke.terrazzo.dashboard.rememberStudioDesignKey
import ee.schimke.terrazzo.discovery.DiscoveryScreen
import ee.schimke.terrazzo.shared.AppScreen
import ee.schimke.terrazzo.shared.CardSelectionException
import ee.schimke.terrazzo.shared.CardStudioDefaults
import ee.schimke.terrazzo.shared.CardStudioScreen
import ee.schimke.terrazzo.shared.HomeDesignServer
import ee.schimke.terrazzo.shared.TerrazzoNavigation
import ee.schimke.terrazzo.shared.rememberAgentConnected
import ee.schimke.terrazzo.wearsync.wearInstallPrompts
import ee.schimke.terrazzo.widget.WidgetActionRunner
import ee.schimke.terrazzo.widget.WidgetInstallSheet
import ee.schimke.terrazzo.widget.WidgetOpenRequests
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import ee.schimke.terrazzo.widget.WidgetsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
fun TerrazzoApp(sessionHolder: SessionHolder, initialDashboard: String? = null) {
  // The session is hoisted into [SessionHolder] (a ViewModel) so it
  // survives rotation. MainActivity seeds it with a cache-only session
  // for the last-known instance, so the first frame paints from disk,
  // and runs the cold-start resume there. A new sign-in or demo toggle
  // replaces it here.
  var session by sessionHolder::session
  var lastError by remember { mutableStateOf<Throwable?>(null) }

  val graph = LocalTerrazzoGraph.current
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val widgetScheduler = remember(context) { WidgetRefreshScheduler(context.applicationContext) }
  val wearSync = graph.wearSyncManager
  val imageStack = LocalHaImageStack.current

  // Sign out of [signedOut]'s instance: wipe its cached dashboards / snapshots and refresh-token
  // vault entry so the next user on this device can't read the previous account's data, and so
  // cold start goes back to discovery. [reason] is shown on the sign-in screen.
  // What this device keeps for [baseUrl]'s account: its dashboard choices, cached dashboards and
  // snapshots, pictures, refresh token and remote URL. With no instance, just the dashboard
  // choices.
  suspend fun forgetInstance(baseUrl: String?) {
    graph.preferencesStore.clearLastViewedDashboard()
    graph.preferencesStore.clearSelectedDashboardUrls()
    if (baseUrl == null) return
    graph.offlineCache.clearInstance(baseUrl)
    // Its pictures too: camera frames and people outlive the dashboards otherwise.
    runCatching { imageStack?.clearCaches() }
    runCatching { graph.tokenVault.clear(baseUrl) }
    graph.remoteUrlStore.clear(baseUrl)
  }

  fun signOut(signedOut: HaSession?, reason: Throwable? = null) {
    // Delete the account's dashboards, values and pins from the watch too.
    wearSync.clearWatchData()
    scope.launch {
      graph.preferencesStore.setDemoMode(false)
      // While the session can still reach Home Assistant: the watches' own tokens.
      if (signedOut != null && signedOut !is DemoHaSession) {
        runCatching { wearSync.revokeWatchAccess(signedOut) }
      }
      forgetInstance(signedOut?.baseUrl)
      signedOut?.close()
    }
    widgetScheduler.cancelDemo()
    if (session === signedOut) session = null
    lastError = reason
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

  // Widget taps send their calls on this session while it is connected, instead of opening one.
  LaunchedEffect(session) { ee.schimke.terrazzo.widget.WidgetActionRunner.setAppSession(session) }

  // Home Assistant's notify.mobile_app_<device> messages arrive on this session's socket; post them
  // once the user allows notifications.
  val appContext = LocalContext.current.applicationContext
  LaunchedEffect(session) {
    val s = session ?: return@LaunchedEffect
    ee.schimke.terrazzo.notify.HaPushReceiver.run(appContext, graph, s)
  }

  // The routine refresh runs while there is a widget, on the launcher or a watch, to refresh.
  LaunchedEffect(Unit) {
    kotlinx.coroutines.flow
      .combine(graph.widgetStore.installed, graph.wearSyncManager.watchWidgets) { home, watch ->
        home.isNotEmpty() to watch.isNotEmpty()
      }
      .distinctUntilChanged()
      .collect { ee.schimke.terrazzo.widget.WidgetRoutineRefreshWorker.sync(appContext) }
  }

  // The launcher and watch widgets follow this session's states, holding the Terrazzo
  // integration's lease once registered so it sends no state wakes meanwhile.
  LaunchedEffect(session) {
    val s = session ?: return@LaunchedEffect
    ee.schimke.terrazzo.widget.LiveWidgetUpdates.follow(appContext, s)
  }

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

  // A sign-in ends the pending re-auth (MainActivity's resume found the restored instance but no
  // token). Signing in to that instance keeps everything restored for it. Signing in somewhere
  // else, or to the demo, forgets it as a sign-out would (its dashboard choices would otherwise
  // hide the new instance's dashboards), so a home the user moved away from isn't left behind.
  fun signedIn(baseUrl: String?) {
    val restored = sessionHolder.reauthBaseUrl ?: return
    sessionHolder.reauthBaseUrl = null
    if (restored == baseUrl) return
    wearSync.clearWatchData()
    scope.launch { forgetInstance(restored) }
  }

  val login =
    rememberLoginController(
      onReady = { baseUrl, accessToken ->
        signedIn(baseUrl)
        session = graph.sessionFactory.create(baseUrl, accessToken)
      },
      onError = { lastError = it },
    )
  val connectionPolicy = graph.lanConnectionPolicy
  var liveDemoSigningIn by remember { mutableStateOf(false) }
  // A live-demo sign-in in flight: choosing another way in cancels it, so it can't replace that
  // session when it finishes.
  var liveDemoLogin by remember { mutableStateOf<Job?>(null) }
  fun cancelLiveDemo() {
    liveDemoLogin?.cancel()
    liveDemoLogin = null
    liveDemoSigningIn = false
  }

  when (val s = session) {
    null ->
      UnauthenticatedScreen(
        onInstancePicked = { baseUrl ->
          cancelLiveDemo()
          when (val verdict = connectionPolicy.check(baseUrl)) {
            LanConnectionPolicy.Verdict.Allow -> login.start(baseUrl)
            is LanConnectionPolicy.Verdict.Deny -> lastError = IllegalStateException(verdict.reason)
          }
        },
        onDemoSelected = {
          cancelLiveDemo()
          signedIn(null)
          scope.launch { graph.preferencesStore.setDemoMode(true) }
          session = graph.sessionFactory.createDemo()
          widgetScheduler.scheduleDemo()
        },
        onLiveDemo = { username, password ->
          val baseUrl = HaAuthService.DEMO_SERVER
          cancelLiveDemo()
          liveDemoSigningIn = true
          liveDemoLogin = scope.launch {
            // A password sign-in, with no browser: the demo's gateway only lets its own frontend
            // sign in, so the tokens are issued to that client and refresh as it (TokenVault).
            val tokens =
              try {
                graph.authService.passwordLogin(baseUrl, username, password)
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                lastError = e
                null
              }
            liveDemoSigningIn = false
            liveDemoLogin = null
            if (tokens == null || session != null) return@launch
            val refresh = tokens.refreshToken
            val access = tokens.accessToken
            if (refresh == null || access == null) {
              lastError = IllegalStateException("The demo returned incomplete tokens")
            } else {
              graph.tokenVault.put(baseUrl, refresh)
              graph.tokenVault.putClientId(baseUrl, HaAuthService.passwordClientId(baseUrl))
              signedIn(baseUrl)
              session = graph.sessionFactory.create(baseUrl, access)
            }
          }
        },
        liveDemoSigningIn = liveDemoSigningIn,
        reauthBaseUrl = sessionHolder.reauthBaseUrl,
        error = lastError,
      )
    else ->
      AuthenticatedShell(
        session = s,
        initialDashboard = initialDashboard,
        onSignOut = { signOut(session) },
      )
  }
}

@Composable
private fun UnauthenticatedScreen(
  onInstancePicked: (String) -> Unit,
  onDemoSelected: () -> Unit,
  onLiveDemo: (username: String, password: String) -> Unit,
  liveDemoSigningIn: Boolean,
  reauthBaseUrl: String?,
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
    onLiveDemo = onLiveDemo,
    liveDemoSigningIn = liveDemoSigningIn,
    reauthBaseUrl = reauthBaseUrl,
    snackbarHost = { SnackbarHost(hostState = snackbars) },
  )
}

@Composable
private fun AuthenticatedShell(
  session: HaSession,
  initialDashboard: String?,
  onSignOut: () -> Unit,
) {
  val context = LocalContext.current
  val graph = LocalTerrazzoGraph.current
  val scope = rememberCoroutineScope()

  val backStack =
    rememberSaveable(
      saver =
        androidx.compose.runtime.saveable.listSaver(
          save = { list -> list.map { it.name } },
          restore = { names ->
            androidx.compose.runtime.mutableStateListOf<AppScreen>().apply {
              addAll(names.mapNotNull { runCatching { AppScreen.valueOf(it) }.getOrNull() })
              if (isEmpty()) add(AppScreen.Dashboards)
            }
          },
        ),
    ) {
      androidx.compose.runtime.mutableStateListOf(AppScreen.Dashboards)
    }
  val navigateTo: (AppScreen) -> Unit = { target ->
    if (backStack.lastOrNull() != target) backStack.add(target)
  }
  val navigateBack: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

  // A widget's more-info or navigate tap opens on the dashboard, whichever screen was showing.
  val widgetOpen by WidgetOpenRequests.requests.collectAsStateWithLifecycle()
  LaunchedEffect(widgetOpen) {
    if (widgetOpen != null && backStack.lastOrNull() != AppScreen.ChooseDashboards) {
      while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
  }

  // Snapshot of the persisted selection at the moment the user
  // entered the ChooseDashboards screen. Captured here (rather than
  // observed via `collectAsState` inside the screen) because
  // DataStore reads are async: a Flow-based read would start as
  // `null`, the screen would default to "all checked", and the
  // saved subset would silently clobber the user's existing choice
  // when the real value arrived after composition.
  var selectionInitial by remember { mutableStateOf<Set<String>?>(null) }
  var selectionInitialResolved by remember { mutableStateOf(false) }

  // Demo mode has a curated dashboard set: nothing to choose.
  val manageDashboards: (() -> Unit)? =
    if (session is DemoHaSession) null
    else {
      {
        selectionInitialResolved = false
        scope.launch {
          selectionInitial = graph.preferencesStore.selectedDashboardUrlsNow()
          selectionInitialResolved = true
        }
        navigateTo(AppScreen.ChooseDashboards)
      }
    }

  TerrazzoNavigation(backStack, onBack = navigateBack) { destination ->
    when (destination) {
      AppScreen.Dashboards ->
        DashboardsRoot(
          session = session,
          initialDashboard = initialDashboard,
          onOpenSettings = { navigateTo(AppScreen.Settings) },
          onOpenWidgets = { navigateTo(AppScreen.Widgets) },
          onOpenRoom = { navigateTo(AppScreen.Room) },
          onSignOut = onSignOut,
        )
      AppScreen.Settings -> {
        val wearReady by graph.wearSyncManager.wearableAvailable.collectAsStateWithLifecycle()
        SettingsScreen(
          session = session,
          onBack = navigateBack,
          onManageDashboards = manageDashboards,
          onOpenLogs = { navigateTo(AppScreen.Logs) },
          onOpenSyncDiagnostics =
            if (wearReady) {
              { navigateTo(AppScreen.SyncDiagnostics) }
            } else null,
        )
      }
      AppScreen.Widgets -> WidgetsScreen(onBack = navigateBack)
      AppScreen.SyncDiagnostics -> {
        val streaming by graph.wearSyncManager.streamActive.collectAsStateWithLifecycle()
        ee.schimke.terrazzo.wearsync.SyncDiagnosticsScreen(
          statsStore = graph.syncStatsStore,
          streamActive = streaming,
          onBack = navigateBack,
        )
      }
      AppScreen.Logs -> ee.schimke.terrazzo.logs.LogsScreen(onBack = navigateBack)
      AppScreen.Room -> {
        // The same lock the dashboard's top bar and Settings set: locked shows, doesn't send.
        val permanentWrite by
          graph.preferencesStore.permanentWriteMode.collectAsStateWithLifecycle(
            initialValue = false,
          )
        val sessionWrite by graph.sessionWriteMode.writeMode.collectAsStateWithLifecycle()
        ee.schimke.terrazzo.room.RoomScreen(
          session = session,
          readOnly = !(sessionWrite ?: permanentWrite),
          onBack = navigateBack,
        )
      }
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
            onBack = navigateBack,
            title = "Manage dashboards",
          )
        } else {
          DashboardSelectionScreen(
            state = rawList,
            initialSelection = selectionInitial,
            onConfirm = { urls ->
              scope.launch { graph.preferencesStore.setSelectedDashboardUrls(urls) }
              navigateBack()
            },
            onBack = navigateBack,
            title = "Manage dashboards",
          )
        }
      }
    }
  }
}

/**
 * The dashboard-first shell. Loads the dashboards list once for the session and renders
 * [DashboardTopBar] with the [DashboardSwitcher] + [TopBarOverflowMenu].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardsRoot(
  session: HaSession,
  initialDashboard: String?,
  onOpenSettings: () -> Unit,
  onOpenWidgets: () -> Unit,
  onOpenRoom: () -> Unit,
  onSignOut: () -> Unit,
) {
  val graph = LocalTerrazzoGraph.current
  val scope = rememberCoroutineScope()
  // Bumped once a dashboard the dashboard agent built is created, to list it.
  var dashboardListRefresh by remember { mutableIntStateOf(0) }
  val dashboards by rememberSelectedDashboardListState(session, dashboardListRefresh)
  val context = LocalContext.current
  val readyDashboards = (dashboards as? DashboardListState.Ready)?.dashboards.orEmpty()
  var opened by rememberSaveable {
    mutableStateOf(preferredDashboardPath(readyDashboards, initialDashboard))
  }
  // The view a card's navigation asked for in the opened dashboard; the switcher clears
  // it.
  var openedView by rememberSaveable { mutableStateOf<String?>(null) }
  // Two-layer write-mode state:
  //   - `permanentWriteMode` is the persisted opt-in (Settings).
  //   - `sessionWriteMode` is a process-scoped override the chip writes
  //     to. Null means "no override yet" — fall back to the permanent
  //     setting, which on a fresh cold start is the read-only default.
  val permanentWrite by
    graph.preferencesStore.permanentWriteMode.collectAsStateWithLifecycle(initialValue = false)
  val sessionWrite by graph.sessionWriteMode.writeMode.collectAsStateWithLifecycle()
  val writeMode = sessionWrite ?: permanentWrite
  val readOnly = !writeMode
  var installPending by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  // Long-press opens a per-card history screen (card + history chart +
  // links). Holds the pressed card plus the snapshot it was pressed
  // against, so the preview at the top matches the dashboard frame.
  var historyTarget by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  // Long-press first offers the card's actions (CardActionsSheet): home screen, watch, style,
  // history, Card Studio. Each target below is one of those, opened from the sheet.
  var actionsTarget by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  // The cards of the section the actions' card was pressed in (none outside a section), so its
  // widget can be the whole section, the card, or one cell of it.
  var actionsSection by remember { mutableStateOf<List<CardConfig>>(emptyList()) }
  var studioTarget by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  var styleTarget by remember { mutableStateOf<Pair<CardConfig, HaSnapshot>?>(null) }
  // The dashboard editor for the opened dashboard, and a count of saves that re-keys it to reload.
  var editorOpen by remember { mutableStateOf(false) }
  // The agent sessions screen, and the section it was asked about (null: the whole dashboard).
  var agentSessions by remember { mutableStateOf<AgentSessionsTarget?>(null) }
  var dashboardSaves by remember { mutableIntStateOf(0) }
  val studioServer by
    graph.preferencesStore.designServerUrl.collectAsStateWithLifecycle(
      initialValue = "https://ha.coo.ee/designs/v1",
    )
  // "Ask your agent" and "Plan a card" are offered only while an AI agent is connected (Settings →
  // Designers), on the server Card Studio talks to.
  val agentServer = studioServer.ifBlank { "https://ha.coo.ee" }
  val agentConnected = rememberAgentConnected(agentServer)
  val designToken by
    graph.preferencesStore.designToken.collectAsStateWithLifecycle(initialValue = "")
  val privateDesignServer by
    graph.preferencesStore.privateDesignServer.collectAsStateWithLifecycle(initialValue = "")
  // This Home Assistant's own design server, when the one it found is this session's.
  val homeDesignServer =
    remember(session, privateDesignServer) {
      session
        .takeIf { privateDesignServer.isNotBlank() && it.baseUrl == privateDesignServer }
        ?.let { home ->
          HomeDesignServer(
            haBaseUrl = home.baseUrl,
            accessToken = { WidgetActionRunner.accessToken(graph, home.baseUrl) },
            link = { path -> home.terrazzo?.privateDesignLink(path) },
          )
        }
    }
  val studioDefaults by
    graph.preferencesStore.cardStudioDefaults.collectAsStateWithLifecycle(
      initialValue = null as String?,
    )
  val designSignIn = rememberDesignSignIn()
  val openedValue = opened

  var startupResolved by rememberSaveable { mutableStateOf(dashboards is DashboardListState.Ready) }
  LaunchedEffect(dashboards) {
    if (!startupResolved && dashboards is DashboardListState.Ready) {
      opened = preferredDashboardPath(readyDashboards, opened)
      startupResolved = true
    }
  }

  // A widget's tap opens on a dashboard ([DashboardViewScreen] opens it): back out of the card
  // screens over it. A widget made from a dashboard's card opens that dashboard, where the card is;
  // one this instance doesn't show (another instance's, or no longer listed) opens where it is.
  val widgetOpen by WidgetOpenRequests.requests.collectAsStateWithLifecycle()
  LaunchedEffect(widgetOpen, dashboards) {
    val target = widgetOpen ?: return@LaunchedEffect
    historyTarget = null
    actionsTarget = null
    studioTarget = null
    styleTarget = null
    editorOpen = false
    val card = target.card ?: return@LaunchedEffect
    when {
      card.baseUrl != session.baseUrl -> WidgetOpenRequests.dropCard(target)
      dashboards is DashboardListState.Loading -> Unit
      readyDashboards.none { it.urlPath == card.dashboardUrlPath } ->
        WidgetOpenRequests.dropCard(target)
      opened != card.dashboardUrlPath -> {
        startupResolved = true
        opened = card.dashboardUrlPath
        openedView = null
        graph.preferencesStore.setLastViewedDashboard(card.dashboardUrlPath)
      }
    }
  }
  val connectionStatus = rememberMobileConnectionStatus(session)
  val snackbars = remember { SnackbarHostState() }
  // Offers the watch app on a connected watch without it.
  val installOnWatch = wearInstallPrompts(snackbars)
  val lifecycleOwner = LocalLifecycleOwner.current
  val activity = LocalActivity.current
  val notifications by session.notifications.collectAsStateWithLifecycle()

  // Snackbar pop on arrival: remember the ids we've already announced, per instance and across
  // launches ([SeenNotifications]), so a refetch or the next cold start doesn't re-fire them. The
  // first list an instance ever shows is taken as already there, as it was when the user opened the
  // app; an empty one seeds nothing, since it is usually a list that hasn't been fetched yet.
  val seenNotifications = remember(context) { SeenNotifications(context) }
  LaunchedEffect(notifications, session.baseUrl) {
    val current = notifications.map { it.notificationId }.toSet()
    val previous = seenNotifications.seen(session.baseUrl)
    if (previous == null) {
      if (current.isNotEmpty()) seenNotifications.record(session.baseUrl, current)
      return@LaunchedEffect
    }
    val fresh = notifications.filter { it.notificationId !in previous }
    if (current.isNotEmpty() || fresh.isNotEmpty()) {
      seenNotifications.record(session.baseUrl, current)
    }
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
  //    a neutral "Disconnected" instead. A configuration change
  //    (rotation) isn't leaving: the session outlives the Activity in
  //    [SessionHolder], so keep the socket for the recreated one.
  val networkChanges = graph.lanConnectionPolicy.networkChanges
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
          // Wait out the backoff, or less: the network coming back (or changing) retries at once.
          val network = networkChanges.value
          withTimeoutOrNull(backoff.next()) { networkChanges.first { it != network } }
          if (session.connectionStatus.value == SessionConnectionStatus.Failed) {
            runCatching { session.connect() }
          }
          if (session.connectionStatus.value == SessionConnectionStatus.Connected) backoff.reset()
        }
      } finally {
        // repeatOnLifecycle has cancelled us; release the widget lease while the socket is still
        // open, park it cleanly, and keep following in the background if a widget entity is moving.
        if (activity?.isChangingConfigurations != true) {
          withContext(NonCancellable) {
            ee.schimke.terrazzo.widget.LiveWidgetUpdates.parkAndHandOff(context, session)
          }
        }
      }
    }
  }

  // Mirror live connection-status transitions into the debug log
  // buffer so the Logs view (when enabled) shows reconnect timing.
  // StateFlow already de-duplicates identical emissions, so each
  // entry here corresponds to a real transition.
  val logStore = graph.logStore
  // Whether this Home Assistant runs its own design server (the Card Designs add-on), asked on each
  // connect so installing or removing the add-on takes effect then. The design choice follows the
  // preference, and a change syncs (TerrazzoApplication). The socket can drop between Connected and
  // the reply (backgrounding disconnects it); that fails the call rather than answering it, so keep
  // the preference as it is and ask again on the next connect.
  LaunchedEffect(session) {
    if (session is DemoHaSession) return@LaunchedEffect
    session.connectionStatus.collectLatest { status ->
      if (status != SessionConnectionStatus.Connected) return@collectLatest
      val available =
        try {
          session.terrazzo?.privateDesignServer() == true
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return@collectLatest
        }
      val found = if (available) session.baseUrl else ""
      if (graph.preferencesStore.privateDesignServer.first() != found) {
        graph.preferencesStore.setPrivateDesignServer(found)
      }
    }
  }
  val sharedSelectionOwner = remember(session) { Any() }
  LaunchedEffect(session) {
    val selections = graph.cardDesignOverrideStore
    val owner = sharedSelectionOwner
    try {
      selections.beginShared(owner)
      if (session !is DemoHaSession) {
        session.connectionStatus.collectLatest { status ->
          if (status == SessionConnectionStatus.Connected) {
            while (true) {
              try {
                val terrazzo = session.terrazzo
                if (terrazzo != null && terrazzo.privateDesignServer()) {
                  terrazzo.sharedDesignSelections()?.let { selections.setShared(owner, it) }
                } else selections.setShared(owner, emptyMap())
              } catch (e: CancellationException) {
                throw e
              } catch (_: Exception) {
                // A temporary outage leaves the last answer until the next refresh.
              }
              delay(30_000)
            }
          }
        }
      }
    } finally {
      withContext(NonCancellable) { selections.endShared(owner) }
    }
  }
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

  // Offline, the dashboard shows its cached snapshot (live pushes are saved to it as they come):
  // the strip says how old that is. Demo boards are never cached.
  val cachedDataAtMs =
    remember(connectionStatus, openedValue, session) {
      if (connectionStatus != ConnectionStatus.Failed || session is DemoHaSession) {
        null
      } else {
        graph.offlineCache.snapshotSavedAtMs(session.baseUrl, openedValue)
      }
    }

  val historyOverlay = historyTarget
  val studioOverlay = studioTarget
  val uriHandler = LocalUriHandler.current
  // The conversation agent answering the integration's edit sessions: while one does, Edit opens a
  // chat with it, and the editor stays as Edit by hand.
  val dashboardAgent by
    produceState(DashboardAgent.Off, session, connectionStatus) {
      if (connectionStatus == ConnectionStatus.Connected)
        value =
          try {
            session.terrazzo?.dashboardAgent() ?: DashboardAgent.Off
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            value
          }
    }
  val phoneAgentEdits = ee.schimke.terrazzo.dashboard.rememberPhoneAgentEdits(session)
  // The dashboard chat: Home Assistant's agent, told what this dashboard shows. Kept above the
  // session screen a draft opens, so coming back carries the same conversation and session on.
  val shown = remember(openedValue) { ShownDashboard() }
  val chat =
    ee.schimke.terrazzo.shared.rememberDashboardChat(session, openedValue) {
      text,
      urlPath,
      view,
      entityIds,
      conversationId,
      sessionId ->
      session.terrazzo?.dashboardChat(text, urlPath, view, entityIds, conversationId, sessionId)
        ?: throw IllegalStateException("The dashboard chat needs the Terrazzo integration")
    }
  var newDashboardOpen by remember { mutableStateOf(false) }
  if (newDashboardOpen && phoneAgentEdits != null)
    // Also where a dashboard still being built is picked back up: the switcher lists only
    // dashboards Home Assistant has.
    ee.schimke.terrazzo.dashboard.NewDashboardChooser(
      phoneAgentEdits.edits,
      dashboardAgent,
      onDismiss = { newDashboardOpen = false },
      onOpen = {
        newDashboardOpen = false
        agentSessions = AgentSessionsTarget(null, urlPath = it.urlPath, sessionId = it.id)
      },
    )
  val sessionsTarget = agentSessions
  if (sessionsTarget != null && phoneAgentEdits != null) {
    val sessionPath = if (sessionsTarget.sessionId != null) sessionsTarget.urlPath else openedValue
    ee.schimke.terrazzo.dashboard.AgentSessionsScreen(
      session = session,
      edits = phoneAgentEdits,
      urlPath = sessionPath,
      title = readyDashboards.firstOrNull { it.urlPath == sessionPath }?.title,
      saveBlockedReason =
        when {
          session is DemoHaSession -> "Demo dashboards can't be saved."
          readOnly -> "Turn on write mode to save."
          else -> null
        },
      startScope = sessionsTarget.scope,
      onBack = { agentSessions = null },
      onApplied = {
        dashboardSaves++
        dashboardListRefresh++
      },
      initialSessionId = sessionsTarget.sessionId,
      dashboardAgent = dashboardAgent,
    )
  } else if (editorOpen) {
    DashboardEditorScreen(
      session = session,
      urlPath = openedValue,
      title = readyDashboards.firstOrNull { it.urlPath == openedValue }?.title,
      saveBlockedReason =
        when {
          session is DemoHaSession -> "Demo dashboards can't be saved."
          readOnly -> "Controls are locked: unlock them in the top bar to save."
          else -> null
        },
      onBack = { editorOpen = false },
      onSaved = { dashboardSaves++ },
      dashboardAgent = dashboardAgent.enabled,
    )
  } else if (studioOverlay != null && studioDefaults != null) {
    val (card, snapshot) = studioOverlay
    BackHandler { studioTarget = null }
    CardStudioScreen(
      card,
      snapshot,
      onBack = { studioTarget = null },
      initialServerUrl = studioServer.ifBlank { "https://ha.coo.ee" },
      designToken = designToken,
      // A Custom Tab: it signs the browser in to UI Builder too, so the editor opens signed in.
      onSignIn = { designSignIn(studioServer.ifBlank { "https://ha.coo.ee/designs/v1" }) },
      account = designTokenLogin(designToken),
      defaults = CardStudioDefaults.decode(studioDefaults.orEmpty()),
      onDefaultsChange = {
        scope.launch { graph.preferencesStore.setCardStudioDefaults(it.encode()) }
      },
      openEditorInBrowser = true,
      designKey = rememberStudioDesignKey(card, snapshot),
      // Downloads the published design and switches this card to it, as the card's skin picker
      // does, even once the studio has closed.
      onPublished = { designId ->
        if (homeDesignServer == null) SkinInstallWorker.enqueue(context, card, designId)
        else {
          val choice = CardDesignSync.choice(context, graph.preferencesStore).first()
          if (Skins.install(context, choice, designId) != Skins.Installed.Yes)
            throw CardSelectionException(
              "The design was published but could not be downloaded. Try again.",
            )
          try {
            val terrazzo =
              session.terrazzo
                ?: throw UnsupportedOperationException("Shared card selections are unavailable")
            terrazzo.setSharedDesignSelection(cardFingerprint(card), designId)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            throw CardSelectionException(
              "The design was downloaded but Home Assistant couldn't select it. Try again.",
            )
          }
          val overrides = graph.cardDesignOverrideStore
          overrides.setPublishedShared(sharedSelectionOwner, cardFingerprint(card), designId)
          WidgetRefreshScheduler(context).refreshAllNow()
        }
      },
      homeServer = homeDesignServer,
      refreshSnapshot = {
        val current = session.loadDashboard(openedValue).second
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
      onShowActions = { actionsTarget = historyCard to historySnapshot },
    )
  } else {
    Scaffold(
      topBar = {
        DashboardTopBar(
          title = {
            DashboardSwitcher(
              dashboards = readyDashboards,
              currentUrlPath = openedValue,
              onSwitch = { newPath ->
                startupResolved = true
                opened = newPath
                openedView = null
                scope.launch { graph.preferencesStore.setLastViewedDashboard(newPath) }
              },
            )
          },
          notifications = notifications,
          onDismissNotification = { n ->
            scope.launch { runCatching { session.dismissNotification(n.notificationId) } }
          },
          onClearNotifications = {
            scope.launch { runCatching { session.dismissAllNotifications() } }
          },
          connectionStatus = connectionStatus,
          onRetryConnection = onRetryConnection,
          cachedDataAtMs = cachedDataAtMs,
          writeMode = writeMode,
          onWriteModeChange = { graph.sessionWriteMode.set(it) },
          overflowMenu = {
            TopBarOverflowMenu(
              onOpenSettings = onOpenSettings,
              onOpenWidgets = onOpenWidgets,
              onSignOut = onSignOut,
              signOutLabel = if (session is DemoHaSession) "Exit demo" else "Sign out",
              onInstallOnWatch = installOnWatch,
              // With a dashboard agent answering, Edit opens a chat with it; the editor stays as
              // Edit by hand.
              onEditDashboard =
                if (dashboardAgent.enabled && phoneAgentEdits != null)
                  ({ agentSessions = AgentSessionsTarget(null) })
                else ({ editorOpen = true }),
              editDashboardLabel =
                if (dashboardAgent.enabled) ee.schimke.terrazzo.dashboard.CHAT_WITH_AGENT
                else "Edit dashboard",
              onNewDashboard =
                if (dashboardAgent.enabled && phoneAgentEdits != null) ({ newDashboardOpen = true })
                else null,
              onEditByHand =
                if (dashboardAgent.enabled && phoneAgentEdits != null) ({ editorOpen = true })
                else null,
              onOpenRoom = onOpenRoom,
              // An administrator's way to set the agent up while it isn't: Home Assistant's
              // settings for it, in the browser.
              onSetUpAgent =
                if (session is DemoHaSession) null
                else
                  dashboardAgent.setupUrl(session.baseUrl)?.let { url ->
                    { uriHandler.openUri(url) }
                  },
              setUpAgentLabel = dashboardAgent.setupLabel ?: "Set up a chat agent",
            )
          },
        )
      },
      snackbarHost = { SnackbarHost(hostState = snackbars) },
      // Scaffold consumes the top inset for the bar; we hand the
      // bottom + sides to the content as the inner contentPadding.
      contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
      // Key on the opened dashboard so switching dashboards
      // disposes the previous one's whole render subtree —
      // crucially the per-card `AndroidView` players — instead of
      // reusing their composition slots. The players are hosted via
      // `AndroidView(factory = { view })`, whose factory runs once
      // per node; without a fresh node on switch, a reused slot
      // keeps showing the previous dashboard's view until a
      // collapse/expand forces recreation. Re-seeding from the
      // offline cache makes the new dashboard paint immediately.
      Box(Modifier.fillMaxSize()) {
        key(openedValue, dashboardSaves) {
          DashboardViewScreen(
            session = session,
            urlPath = openedValue,
            readOnly = readOnly,
            connectionPending = connectionStatus == ConnectionStatus.Connecting,
            // A tap dropped by the lock says so where it was made, with Unlock right there.
            unlockSnackbars = snackbars,
            onUnlock = { graph.sessionWriteMode.set(true) },
            // Long-press opens the card's actions, handed the live
            // snapshot the card was pressed against so a fork or the
            // history preview matches the dashboard frame.
            onCardLongPress = { card, snapshot, section ->
              actionsTarget = card to snapshot
              actionsSection = section
            },
            contentPadding = padding,
            initialViewPath = openedView,
            // A card's navigation to another dashboard the user can open switches to it.
            onOpenDashboard = { urlPath, viewPath ->
              val known = readyDashboards.any { it.urlPath == urlPath }
              if (known) {
                opened = urlPath
                openedView = viewPath
                scope.launch { graph.preferencesStore.setLastViewedDashboard(urlPath) }
              }
              known
            },
            // Once shown, the view isn't asked for again when the screen is recreated.
            onInitialViewShown = { openedView = null },
            canPlanCards = agentConnected,
            onAskAgentAboutSection =
              // Home Assistant's own conversation agent answers this one.
              if (phoneAgentEdits != null && dashboardAgent.enabled)
                ({ view, section, title ->
                  agentSessions =
                    AgentSessionsTarget(
                      ee.schimke.ha.client.dashboard.EditScope.section(view, section, title),
                    )
                })
              else null,
            onShown = { dashboard, snapshot ->
              shown.dashboard = dashboard
              shown.snapshot = snapshot
            },
          )
        }
        if (dashboardAgent.chatAvailable && session.terrazzo != null && session !is DemoHaSession)
          ee.schimke.terrazzo.shared.DashboardChat(
            dashboardAgent,
            chat,
            screen = { shown.screen() },
            // The phone shows a draft in its session, with the dashboard's own cards.
            onDraft = { id ->
              agentSessions = AgentSessionsTarget(null, urlPath = openedValue, sessionId = id)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(padding).padding(16.dp),
          )
      }
    }
  }

  val dashboardUrlPath = openedValue ?: ""
  actionsTarget?.let { target ->
    val (card, snapshot) = target
    // Read as the sheet opens, so an agent connected since the dashboard opened counts.
    val agentForCard = rememberAgentConnected(agentServer)
    // Settings → Designers can offer the studio with no agent too.
    val studioEntry by
      graph.preferencesStore.cardStudioEntry.collectAsStateWithLifecycle(initialValue = false)
    CardActionsSheet(
      baseUrl = session.baseUrl,
      dashboardUrlPath = dashboardUrlPath,
      card = card,
      snapshot = snapshot,
      onAddToHomeScreen = { installPending = target },
      onChangeStyle = { styleTarget = target },
      // Reopened from the history screen, the sheet doesn't offer the screen it sits on.
      onShowHistory = if (historyTarget == null) ({ historyTarget = target }) else null,
      onOpenInStudio = if (agentForCard || studioEntry) ({ studioTarget = target }) else null,
      studioLabel = if (agentForCard) "Ask your agent" else "Card Studio",
      onDismiss = { actionsTarget = null },
      // Only offer monitoring in demo mode — the service pulls
      // state from DemoData today. Live mode needs a shared
      // non-UI HA session; landing in a follow-up.
      onMonitor =
        if (session is DemoHaSession && graph.cardMonitor.isEnabled) {
          { graph.cardMonitor.start(card) }
        } else null,
    )
  }

  styleTarget?.let { (card, snapshot) ->
    CardStyleSheet(
      session = session,
      card = card,
      snapshot = snapshot,
      onDismiss = { styleTarget = null },
    )
  }

  installPending?.let { (card, snapshot) ->
    WidgetInstallSheet(
      baseUrl = session.baseUrl,
      dashboardUrlPath = openedValue,
      card = card,
      snapshot = snapshot,
      sectionCards = actionsSection.takeIf { card in it }.orEmpty(),
      onDismiss = { installPending = null },
      refreshSnapshot = { session.loadDashboard(openedValue).second },
    )
  }
}

/** Home Assistant refused the stored refresh token; shown on the sign-in screen. */
private class SignedOutException :
  IllegalStateException("Home Assistant signed this device out. Sign in again.")

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

/** What the agent sessions screen opens on: a section's session, or the list ([scope] null). */
/** The dashboard on screen, as the dashboard chat names it; not state: read only when sending. */
private class ShownDashboard {
  var dashboard: ee.schimke.ha.model.Dashboard? = null
  var snapshot: HaSnapshot? = null

  fun screen(): ee.schimke.terrazzo.shared.ChatScreen {
    val dashboard = dashboard ?: return ee.schimke.terrazzo.shared.ChatScreen()
    val states = snapshot?.states.orEmpty()
    return ee.schimke.terrazzo.shared.ChatScreen(
      viewTitle = dashboard.views.singleOrNull()?.title,
      entityIds =
        ee.schimke.terrazzo.core.logs
          .referencedEntities(dashboard)
          .filter { it in states }
          .take(ee.schimke.ha.client.dashboard.DashboardChatReply.MAX_ENTITIES),
    )
  }
}

private data class AgentSessionsTarget(
  val scope: ee.schimke.ha.client.dashboard.EditScope?,
  /** With [sessionId], the dashboard that session is about (a new one's path). */
  val urlPath: String? = null,
  /** A session to open at once, such as a new dashboard just started. */
  val sessionId: String? = null,
)
