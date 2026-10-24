package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.dashboard.CardAddress
import ee.schimke.ha.client.dashboard.DashboardEditSession
import ee.schimke.ha.client.dashboard.toCardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.SectionCardGrid
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.liveDashboardSnapshots
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val TAG = "DashboardEditor"

/**
 * The phone's light dashboard editor (docs/architecture/dashboard-editing.md; the editor itself is
 * `app-ui`'s [DashboardDraftEditor], which the panel shows in its full scope): the dashboard's
 * Lovelace config as a draft, drawn with the same live card previews the dashboard shows, laid out
 * on Home Assistant's section grid ([SectionCardGrid]) so a card's size here is the size it has in
 * HA. Tap a card to select it, then move it, resize it (with the toolbar or by dragging its corner
 * — the cards after it re-flow as it grows), swap its template or delete it; sections move up and
 * down. Nothing reaches Home Assistant until **Save**, which is conditional
 * ([DashboardEditSession]): another client's change since the draft was loaded is refused rather
 * than overwritten.
 *
 * Adding cards, sections, views and dashboards and editing a card's settings belong to Home
 * Assistant's editor or the Terrazzo panel; [IntegrationCard] points there. With the Terrazzo
 * integration installed it also offers **Ask an agent** ([AgentEditScreen]): an edit session in
 * Home Assistant whose drafts are previewed here and applied by an administrator.
 *
 * The dashboard view itself lays sections out by harc's own card designs ([SectionCardFlow]), so it
 * can place cards differently from HA's grid; the editor shows the grid because that is what a
 * card's `grid_options` mean.
 *
 * [saveBlockedReason] says why the draft can't be saved (read-only mode, a demo session); the
 * editor still edits, so a draft can be explored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardEditorScreen(
  session: HaSession,
  urlPath: String?,
  title: String?,
  saveBlockedReason: String?,
  onBack: () -> Unit,
  onSaved: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val snackbars = remember { SnackbarHostState() }
  var load by remember(session, urlPath) { mutableStateOf<EditorLoad>(EditorLoad.Loading) }
  var snapshot by remember(session, urlPath) { mutableStateOf(HaSnapshot()) }
  var integration by remember(session) { mutableStateOf(IntegrationStatus.Unknown) }
  var askingAgent by rememberSaveable(session, urlPath) { mutableStateOf(false) }
  // Bumped when an agent's draft is applied, so the editor reloads what Home Assistant now holds.
  var reloads by remember(session, urlPath) { mutableIntStateOf(0) }
  LaunchedEffect(session) {
    val config = runCatching { session.fetchInstanceConfig() }.getOrNull()
    integration =
      when {
        config == null -> IntegrationStatus.Unknown
        INTEGRATION_DOMAIN in config.components -> IntegrationStatus.Installed
        else -> IntegrationStatus.Missing
      }
  }

  LaunchedEffect(session, urlPath, reloads) {
    load =
      try {
        val stored = session.fetchDashboardConfig(urlPath)
        val config = stored ?: configOf(session.loadDashboard(urlPath).first)
        val reason =
          saveBlockedReason
            ?: if (stored == null) "This session can't save dashboards to Home Assistant." else null
        EditorLoad.Ready(
          DashboardEditSession(config, reason) { expected, replacement ->
            session.compareAndReplaceDashboardConfig(urlPath, expected, replacement)
          }
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Loading $urlPath for editing failed", e)
        EditorLoad.Failed(e.message ?: "Couldn't load this dashboard.")
      }
  }
  // Cards in the editor are live: they keep up with Home Assistant as the dashboard does.
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

  val agentEdits = session.agentEdits
  if (askingAgent && agentEdits != null) {
    val context = LocalContext.current
    BackHandler { askingAgent = false }
    AgentEditScreen(
      edits = agentEdits,
      urlPath = urlPath,
      title = title,
      snapshot = snapshot,
      onBack = { askingAgent = false },
      onSharePrompt = { sharePrompt(context, it) },
      onApplied = {
        reloads++
        onSaved()
      },
      writeBlockedReason = saveBlockedReason,
    ) { _, card ->
      DashboardCardPreview(session, card, snapshot)
    }
    return
  }

  when (val l = load) {
    EditorLoad.Loading ->
      EditorFrame(title = "Edit dashboard", onBack = onBack, snackbars = snackbars) {
        Text("Loading…", Modifier.padding(24.dp))
      }
    is EditorLoad.Failed ->
      EditorFrame(title = "Edit dashboard", onBack = onBack, snackbars = snackbars) {
        Text(l.message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
      }
    is EditorLoad.Ready ->
      DashboardEditor(
        session = session,
        edits = l.edits,
        title = title,
        snapshot = snapshot,
        snackbars = snackbars,
        onBack = onBack,
        onSaved = onSaved,
        launch = { block -> scope.launch { block() } },
        integration = integration,
        urlPath = urlPath,
        onAskAgent = agentEdits?.let { { askingAgent = true } },
      )
  }
}

/** The Terrazzo integration's domain (`custom_components/terrazzo`). */
private const val INTEGRATION_DOMAIN = "terrazzo"

private sealed interface EditorLoad {
  data object Loading : EditorLoad

  data class Failed(val message: String) : EditorLoad

  class Ready(val edits: DashboardEditSession) : EditorLoad
}

private val editorJson = Json {
  prettyPrint = true
  encodeDefaults = false
  explicitNulls = false
}

/** A config for a session that serves dashboards without their stored config (demo mode). */
internal fun configOf(dashboard: Dashboard): JsonObject =
  editorJson.encodeToJsonElement(Dashboard.serializer(), dashboard) as JsonObject

/**
 * The phone's light editor ([EditorScope.Light] of the shared [DashboardDraftEditor]), drawn with
 * the dashboard's own card previews, ending with [IntegrationCard].
 */
@Composable
internal fun DashboardEditor(
  session: HaSession,
  edits: DashboardEditSession,
  title: String?,
  snapshot: HaSnapshot,
  snackbars: SnackbarHostState,
  onBack: () -> Unit,
  onSaved: () -> Unit,
  launch: (suspend () -> Unit) -> Unit,
  initialSelection: CardAddress? = null,
  integration: IntegrationStatus = IntegrationStatus.Unknown,
  urlPath: String? = null,
  onAskAgent: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val editInHomeAssistant =
    homeAssistantEditUrl(session.baseUrl, urlPath)?.let { url -> { openUrl(context, url) } }
  // The agent works from the dashboard in Home Assistant, and leaving for it drops this draft:
  // what to do once the person agrees.
  var confirmAgent by remember { mutableStateOf<(() -> Unit)?>(null) }
  DashboardDraftEditor(
    edits = edits,
    title = title,
    snackbars = snackbars,
    onBack = onBack,
    onSaved = onSaved,
    launch = launch,
    initialSelection = initialSelection,
    backHandler = { back -> BackHandler(onBack = back) },
    footer = { dirty, discardEdits ->
      val ask =
        onAskAgent
          ?.takeIf { integration == IntegrationStatus.Installed }
          ?.let { ask ->
            {
              if (dirty)
                confirmAgent = {
                  discardEdits()
                  ask()
                }
              else ask()
            }
          }
      IntegrationCard(integration, editInHomeAssistant, onAskAgent = ask) {
        openUrl(context, HACS_INSTALL_URL)
      }
    },
  ) { card ->
    DashboardCardPreview(session, card.toCardConfig(), snapshot)
  }

  confirmAgent?.let { go ->
    AlertDialog(
      onDismissRequest = { confirmAgent = null },
      title = { Text("Discard your edits?") },
      text = {
        Text(
          "The agent works from the dashboard saved in Home Assistant. Save your edits first to " +
            "keep them."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmAgent = null
            go()
          }
        ) {
          Text("Discard and ask")
        }
      },
      dismissButton = { TextButton(onClick = { confirmAgent = null }) { Text("Keep editing") } },
    )
  }
}

@Composable
private fun EditorFrame(
  title: String,
  onBack: () -> Unit,
  snackbars: SnackbarHostState,
  content: @Composable () -> Unit,
) {
  @OptIn(ExperimentalMaterial3Api::class)
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbars) },
  ) { padding ->
    Box(Modifier.padding(padding)) { content() }
  }
}

/** Whether this Home Assistant has the Terrazzo integration loaded, as `get_config` reports it. */
enum class IntegrationStatus {
  Installed,
  Missing,
  /** No instance config to ask (demo mode, offline). */
  Unknown,
}

/**
 * Where the editing the phone doesn't do lives: Home Assistant's own dashboard editor for adding
 * cards, sections and dashboards, an agent through the Terrazzo integration ([onAskAgent], when it
 * is installed), and — when it isn't — the integration from HACS.
 */
@Composable
internal fun IntegrationCard(
  status: IntegrationStatus,
  onOpenHomeAssistant: (() -> Unit)?,
  onAskAgent: (() -> Unit)? = null,
  onInstall: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Need more than moving and resizing?", style = MaterialTheme.typography.titleSmall)
      Text(
        "Add cards and sections, change a card's settings or create dashboards in Home " +
          "Assistant's own editor.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      onOpenHomeAssistant?.let { open ->
        OutlinedButton(onClick = open) { Text("Open in Home Assistant") }
      }
      onAskAgent?.let { ask ->
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
          "Or describe the change and let your AI assistant draft it. You preview each draft " +
            "here before it reaches Home Assistant.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = ask) { Text("Ask an agent") }
      }
      if (status != IntegrationStatus.Installed) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
          "The Terrazzo integration, from HACS, shows your dashboards in Home Assistant as harc " +
            "draws them, and lets AI assistants read your home.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onInstall) { Text("Install with HACS") }
      }
    }
  }
}

/** HACS's page for this repository, through My Home Assistant (docs/HOME_ASSISTANT.md). */
internal const val HACS_INSTALL_URL =
  "https://my.home-assistant.io/redirect/hacs_repository/" +
    "?owner=yschimke&repository=homeassistant-remotecompose&category=integration"

/** [urlPath]'s dashboard in Home Assistant's frontend, opened in edit mode. */
internal fun homeAssistantEditUrl(baseUrl: String?, urlPath: String?): String? =
  baseUrl
    ?.trimEnd('/')
    ?.takeIf { it.startsWith("http") }
    ?.let { "$it/${urlPath ?: "lovelace"}/0?edit=1" }

/** The share sheet for an edit session's prompt: it names the session, never a token. */
private fun sharePrompt(context: Context, prompt: String) {
  val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prompt)
  runCatching {
    context.startActivity(
      Intent.createChooser(send, "Share prompt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
    .onFailure { Log.w(TAG, "Nothing shares the prompt", it) }
}

private fun openUrl(context: Context, url: String) {
  runCatching {
    context.startActivity(
      Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
    .onFailure { Log.w(TAG, "Nothing opens $url", it) }
}
