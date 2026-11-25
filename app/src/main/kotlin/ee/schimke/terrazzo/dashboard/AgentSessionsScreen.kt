package ee.schimke.terrazzo.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.client.dashboard.EditScope
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.liveDashboardSnapshots
import ee.schimke.terrazzo.core.session.terrazzo
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * How the phone reaches agent sessions: Home Assistant's own, answered by its conversation agent.
 */
internal class PhoneAgentEdits(val edits: AgentEdits)

/**
 * The agent sessions [session] can hold: the Terrazzo integration's, in Home Assistant; null when
 * this session can't hold any (no integration, or demo mode without it).
 */
@Composable
internal fun rememberPhoneAgentEdits(session: HaSession): PhoneAgentEdits? =
  remember(session) { session.terrazzo?.agentEdits?.let { PhoneAgentEdits(it) } }

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
    urlPath = urlPath,
    title = title,
    snapshot = snapshot,
    onBack = onBack,
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
