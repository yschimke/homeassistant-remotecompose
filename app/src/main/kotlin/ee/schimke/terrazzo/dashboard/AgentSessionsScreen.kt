package ee.schimke.terrazzo.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.CloudDashboardAgentEdits
import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.client.dashboard.EditScope
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.liveDashboardSnapshots
import ee.schimke.terrazzo.core.session.terrazzo
import ee.schimke.terrazzo.shared.AgentOnThisDevice
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** How the phone reaches agent sessions: through the design server, or Home Assistant alone. */
internal class PhoneAgentEdits(val edits: AgentEdits, val relayServer: String?)

/**
 * The agent sessions [session] can hold: through the design server's relay (which Home Assistant
 * also keeps, so an agent signed in to Home Assistant sees the same sessions), else Home
 * Assistant's alone; null when this session can't hold any.
 */
@Composable
internal fun rememberPhoneAgentEdits(
  session: HaSession,
  /** The integration's dashboard agent answers in Home Assistant's own sessions: no relay. */
  dashboardAgent: Boolean = false,
): PhoneAgentEdits? {
  val designServer by
    LocalTerrazzoGraph.current.preferencesStore.designServerUrl.collectAsStateWithLifecycle(
      initialValue = "https://ha.coo.ee",
    )
  val relayServer = designServer.ifBlank { "https://ha.coo.ee" }
  val cloudEdits: CloudDashboardAgentEdits? =
    remember(session, relayServer, dashboardAgent) {
      if (dashboardAgent) null
      else
        session.terrazzo?.cloudAgentEdits(relayServer, AgentOnThisDevice.dashboardGrants()) {
          AgentOnThisDevice.link(relayServer)
        }
    }
  DisposableEffect(cloudEdits) { onDispose { cloudEdits?.close() } }
  return remember(session, cloudEdits) {
    cloudEdits?.let { PhoneAgentEdits(it, relayServer) }
      ?: session.terrazzo?.agentEdits?.let { PhoneAgentEdits(it, null) }
  }
}

/**
 * The phone's agent sessions on one dashboard ([AgentEditScreen]): its open and recent sessions, or
 * straight into one about [startScope] (a section's long press). Drafts are drawn with the
 * dashboard's own cards and live states.
 */
@Composable
internal fun AgentSessionsScreen(
  session: HaSession,
  edits: PhoneAgentEdits,
  urlPath: String?,
  title: String?,
  saveBlockedReason: String?,
  startScope: EditScope?,
  onBack: () -> Unit,
  onApplied: () -> Unit,
  /** Takes a draft into the dashboard editor to adjust by hand ([AgentEditScreen]'s Adjust). */
  onEditDraft: ((draft: JsonObject, baseline: JsonObject?) -> Unit)? = null,
  /** A session to open at once: a new dashboard just started. */
  initialSessionId: String? = null,
  dashboardAgent: DashboardAgent = DashboardAgent.Off,
) {
  val context = LocalContext.current
  BackHandler(onBack = onBack)
  var snapshot by remember(session, urlPath) { mutableStateOf(HaSnapshot()) }
  // Cards in a draft are live: they keep up with Home Assistant as the dashboard does.
  LaunchedEffect(session, urlPath) {
    val loaded =
      runCatching { session.loadDashboard(urlPath).second }
        .recoverCatching { session.loadSnapshot() }
        .getOrNull() ?: return@LaunchedEffect
    snapshot = loaded
    val interval = session.refreshIntervalMillis
    if (interval != null) {
      while (true) {
        delay(interval)
        runCatching { session.loadDashboard(urlPath).second }.onSuccess { snapshot = it }
      }
    }
    session.liveDashboardSnapshots(loaded).collect { snapshot = it }
  }
  AgentEditScreen(
    edits = edits.edits,
    cloudServer = edits.relayServer,
    urlPath = urlPath,
    title = title,
    snapshot = snapshot,
    onBack = onBack,
    onSharePrompt = { sharePrompt(context, it) },
    onApplied = onApplied,
    writeBlockedReason = saveBlockedReason,
    startScope = startScope,
    onEditDraft = onEditDraft,
    initialSessionId = initialSessionId,
    dashboardAgent = dashboardAgent,
  ) { _, card ->
    DashboardCardPreview(session, card, snapshot)
  }
}
