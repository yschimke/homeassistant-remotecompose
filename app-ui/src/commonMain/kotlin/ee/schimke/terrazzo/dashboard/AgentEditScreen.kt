package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.HaCommandException
import ee.schimke.ha.client.dashboard.AgentEditSession
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.DashboardConflictException
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.shared.DashboardContent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Ask an agent to change a dashboard, through the Terrazzo integration's edit sessions
 * (docs/architecture/dashboard-editing.md). The person writes a request and hands the agent the
 * prompt Home Assistant returns — the MCP endpoint and the session id, never a token or the
 * dashboard. The screen then watches the session: each draft the agent submits is drawn with the
 * home's real cards and live states ([renderCard], the dashboard's own renderer), and can be
 * applied (Home Assistant allows administrators only, and only while the dashboard still holds the
 * config the session started from), answered with a note, or discarded. One undo is kept.
 *
 * The phone and the Terrazzo panel both show this; [onSharePrompt] is the platform's share sheet or
 * clipboard. [writeBlockedReason] (the phone's read-only mode, say) withholds Apply and Undo: the
 * session can still be started, drafted and reviewed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
  edits: AgentEdits,
  urlPath: String?,
  title: String?,
  snapshot: HaSnapshot,
  onBack: () -> Unit,
  onSharePrompt: (String) -> Unit,
  onApplied: () -> Unit,
  writeBlockedReason: String? = null,
  renderCard: @Composable (CardKey, CardConfig) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val snackbars = remember { SnackbarHostState() }
  var sessionId by remember(edits, urlPath) { mutableStateOf<String?>(null) }
  var prompt by remember(edits, urlPath) { mutableStateOf<String?>(null) }
  var session by remember(edits, urlPath) { mutableStateOf<AgentEditSession?>(null) }
  var problem by remember(edits, urlPath) { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }

  // A snackbar suspends until it is dismissed: shown on its own, so the buttons come back at once.
  fun tell(message: String) {
    scope.launch { snackbars.showSnackbar(message) }
  }

  fun run(block: suspend () -> Unit) {
    busy = true
    scope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        tell(agentEditFailure(e))
      } finally {
        busy = false
      }
    }
  }

  val id = sessionId
  LaunchedEffect(edits, id) {
    if (id == null) return@LaunchedEffect
    // The subscription ends with its connection: watch again once the app has reconnected.
    while (true) {
      try {
        edits.watch(id).collect {
          session = it
          problem = null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: HaCommandException) {
        problem = e.reason
      } catch (e: Exception) {
        problem = "Reconnecting to Home Assistant…"
      }
      delay(3_000)
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Ask an agent", maxLines = 1) },
        navigationIcon = {
          IconButton(onClick = { if (sessionId != null) sessionId = null else onBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbars) },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
      val current = session?.takeIf { it.id == id }
      when {
        id == null ->
          StartSession(
            edits = edits,
            urlPath = urlPath,
            title = title,
            busy = busy,
            onStart = { request ->
              run {
                val started = edits.start(urlPath, request)
                session = started.session
                prompt = started.prompt
                sessionId = started.session.id
              }
            },
            onResume = { resumed ->
              run {
                val handoff = edits.resume(resumed.id)
                session = handoff.session
                prompt = handoff.prompt.ifBlank { null }
                sessionId = handoff.session.id
              }
            },
          )
        current == null -> Text(problem ?: "Loading the edit session…", Modifier.padding(24.dp))
        else ->
          SessionReview(
            session = current,
            prompt = prompt,
            problem = problem,
            snapshot = snapshot,
            busy = busy,
            writeBlockedReason = writeBlockedReason,
            onSharePrompt = onSharePrompt,
            onApply = { draft ->
              run {
                session = edits.apply(current.id, draft.id)
                onApplied()
                tell("Applied to Home Assistant.")
              }
            },
            onUndo = {
              run {
                session = edits.undo(current.id)
                onApplied()
              }
            },
            onNote = { text -> run { session = edits.note(current.id, text) } },
            onDiscard = {
              run {
                edits.discard(current.id)
                sessionId = null
                session = null
              }
            },
            renderCard = renderCard,
          )
      }
    }
  }
}

/** What to tell the person when Home Assistant refuses an edit-session command. */
internal fun agentEditFailure(e: Exception): String =
  when {
    e is DashboardConflictException -> e.message.orEmpty()
    e is HaCommandException && e.code == "unauthorized" ->
      "Only a Home Assistant administrator can apply or undo a draft."
    e is HaCommandException && e.code == "unknown_command" ->
      "Update the Terrazzo integration in HACS to ask an agent."
    e is HaCommandException -> e.reason
    else -> e.message ?: "Home Assistant didn't answer."
  }

@Composable
private fun StartSession(
  edits: AgentEdits,
  urlPath: String?,
  title: String?,
  busy: Boolean,
  onStart: (String) -> Unit,
  onResume: (AgentEditSession) -> Unit,
) {
  var request by remember { mutableStateOf("") }
  var open by remember(edits, urlPath) { mutableStateOf<List<AgentEditSession>>(emptyList()) }
  LaunchedEffect(edits, urlPath) { open = runCatching { edits.open(urlPath) }.getOrDefault(open) }
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      "What should change on ${title ?: "this dashboard"}?",
      style = MaterialTheme.typography.titleMedium,
    )
    Text(
      "Home Assistant opens an edit session and gives you a prompt for your AI assistant. The " +
        "assistant signs in to Home Assistant itself and can only submit drafts: you see each one " +
        "here, drawn with your cards, before anything changes.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = request,
      onValueChange = { request = it },
      label = { Text("Request") },
      placeholder = { Text("A garden section with soil moisture charts") },
      modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp).heightIn(min = 96.dp),
    )
    Button(enabled = !busy, onClick = { onStart(request) }) { Text("Start edit session") }
    if (open.isNotEmpty()) {
      Text("Open sessions", style = MaterialTheme.typography.titleSmall)
      open.forEach { s ->
        OutlinedButton(onClick = { onResume(s) }, modifier = Modifier.fillMaxWidth()) {
          Text(
            s.request.ifBlank { "No request" } +
              " · ${s.drafts.size} draft${if (s.drafts.size == 1) "" else "s"}",
            maxLines = 1,
          )
        }
      }
    }
  }
}

private val draftJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

/** [config] as the dashboard renderer reads it, or null when it can't be drawn. */
internal fun previewDashboard(config: JsonObject?): Dashboard? = config?.let {
  runCatching { draftJson.decodeFromJsonElement(Dashboard.serializer(), it) }.getOrNull()
}

@Composable
private fun SessionReview(
  session: AgentEditSession,
  prompt: String?,
  problem: String?,
  snapshot: HaSnapshot,
  busy: Boolean,
  writeBlockedReason: String?,
  onSharePrompt: (String) -> Unit,
  onApply: (AgentEditSession.Draft) -> Unit,
  onUndo: () -> Unit,
  onNote: (String) -> Unit,
  onDiscard: () -> Unit,
  renderCard: @Composable (CardKey, CardConfig) -> Unit,
) {
  var shown by remember(session.id) { mutableStateOf<String?>(null) }
  var noting by remember { mutableStateOf(false) }
  // The newest draft unless the person picked another, or the applied one once applied.
  val draft =
    session.drafts.firstOrNull { it.id == (shown ?: session.applied?.draftId) }
      ?: session.latestDraft
  Column(Modifier.fillMaxSize()) {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        session.request.ifBlank { "No request given" },
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        when {
          session.isApplied -> "Applied to Home Assistant."
          !session.isOpen -> "This session was ${session.status}."
          draft == null -> "Waiting for the agent's first draft…"
          else -> draft.summary.ifBlank { "Draft from the agent" }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      writeBlockedReason?.let {
        Text(
          "$it Drafts can be reviewed but not applied.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      val canWrite = !busy && writeBlockedReason == null
      if (prompt != null && session.isOpen && session.drafts.isEmpty()) {
        PromptCard(prompt, onSharePrompt)
      }
      if (session.drafts.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          session.drafts.forEachIndexed { i, d ->
            FilterChip(
              selected = d.id == draft?.id,
              onClick = { shown = d.id },
              label = { Text("Draft ${i + 1}") },
            )
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
          session.isApplied -> OutlinedButton(enabled = canWrite, onClick = onUndo) { Text("Undo") }
          session.isOpen -> {
            if (draft != null) {
              Button(enabled = canWrite, onClick = { onApply(draft) }) { Text("Apply") }
              OutlinedButton(enabled = !busy, onClick = { noting = true }) {
                Text("Request changes")
              }
            }
            TextButton(enabled = !busy, onClick = onDiscard) { Text("Discard") }
          }
        }
      }
      session.notes.lastOrNull()?.let {
        Text(
          "Your last note: ${it.text}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    val dashboard = previewDashboard(draft?.config)
    when {
      draft == null -> Unit
      dashboard == null ->
        Text(
          "This draft can't be drawn here; ask the agent for a dashboard with a views list.",
          Modifier.padding(16.dp),
          color = MaterialTheme.colorScheme.error,
        )
      else ->
        DashboardContent(
          dashboard,
          "agent-draft-${session.id}-${draft.id}",
          snapshot,
          Modifier.weight(1f),
          renderCard,
        )
    }
  }

  if (noting) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { noting = false },
      title = { Text("Request changes") },
      text = {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text("What should the agent change?") },
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        TextButton(
          enabled = text.isNotBlank(),
          onClick = {
            noting = false
            onNote(text)
          },
        ) {
          Text("Send")
        }
      },
      dismissButton = { TextButton(onClick = { noting = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun PromptCard(prompt: String, onSharePrompt: (String) -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        "Give this to your AI assistant. It holds no password or token: the assistant signs in " +
          "to Home Assistant when it connects.",
        style = MaterialTheme.typography.bodySmall,
      )
      SelectionContainer {
        Text(
          prompt,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          maxLines = 8,
        )
      }
      Button(onClick = { onSharePrompt(prompt) }) { Text("Share prompt") }
    }
  }
}
