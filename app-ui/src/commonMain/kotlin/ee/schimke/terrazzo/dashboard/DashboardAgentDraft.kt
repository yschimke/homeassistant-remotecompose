package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.agent.AgentLiveControls
import ee.schimke.ha.client.dashboard.AgentEditSession
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.shared.DashboardContent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The dashboard while an agent works on it (docs/architecture/dashboard-editing.md): the newest
 * open edit session on [urlPath] — started on this device or on any of the person's others — is
 * watched, and its newest draft is drawn in place of the dashboard with the home's live states, as
 * the agent edits it. A bar above says what the agent is doing and offers **Use it**, a switch back
 * to the dashboard as it is, a message box (pinned to a card with that card's **Comment** button)
 * and **Details** for the full session screen. Nothing changes in Home Assistant until Use it.
 *
 * [sources] are asked in order (the cloud relay's sessions, then those of agents signed in to Home
 * Assistant directly); the first open session found is shown. With none it is just [current].
 */
@Composable
fun DashboardAgentDraft(
  sources: List<AgentEdits>,
  urlPath: String?,
  snapshot: HaSnapshot,
  onOpenSession: (String) -> Unit,
  onApplied: () -> Unit,
  modifier: Modifier = Modifier,
  writeBlockedReason: String? = null,
  renderCard: @Composable (CardKey, CardConfig) -> Unit,
  current: @Composable (Modifier) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var edits by remember(sources, urlPath) { mutableStateOf(sources.first()) }
  val live = edits as? AgentLiveControls
  var watchedId by remember(sources, urlPath) { mutableStateOf<String?>(null) }
  var session by remember(sources, urlPath) { mutableStateOf<AgentEditSession?>(null) }
  var problem by remember(sources, urlPath) { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }

  // Look for an open session while there is none: one started elsewhere shows up within a minute.
  LaunchedEffect(sources, urlPath, watchedId) {
    if (watchedId != null) return@LaunchedEffect
    while (true) {
      for (source in sources) {
        val found =
          try {
            source.open(urlPath).firstOrNull()
          } catch (e: CancellationException) {
            throw e
          } catch (_: Throwable) {
            // An older integration, or no connection yet: the dashboard simply shows as it is.
            null
          }
        if (found != null) {
          edits = source
          session = found
          watchedId = found.id
          return@LaunchedEffect
        }
      }
      delay(OPEN_POLL_MILLIS)
    }
  }
  LaunchedEffect(edits, watchedId) {
    val id = watchedId ?: return@LaunchedEffect
    while (true) {
      try {
        edits.watch(id).collect {
          session = it
          problem = null
          // Discarded elsewhere: back to the dashboard as it is.
          if (!it.isOpen && !it.isApplied) {
            session = null
            watchedId = null
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        if (e is ee.schimke.ha.client.agent.AgentSessionEndedException) {
          session = null
          watchedId = null
          return@LaunchedEffect
        }
        problem = e.message ?: "Reconnecting…"
      }
      delay(3_000)
    }
  }

  val shown = session?.takeIf { it.id == watchedId }
  if (shown == null) {
    current(modifier)
    return
  }
  var showDraft by remember(shown.id) { mutableStateOf(true) }
  var target by remember(shown.id) { mutableStateOf<String?>(null) }
  val relay = live?.live(shown.id)?.collectAsState()?.value
  val draft = shown.drafts.firstOrNull { it.id == shown.applied?.draftId } ?: shown.latestDraft
  val dashboard = previewDashboard(draft?.config)

  fun run(block: suspend () -> Unit) {
    busy = true
    scope.launch {
      try {
        block()
        problem = null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        problem = agentEditFailure(e)
      } finally {
        busy = false
      }
    }
  }

  Column(modifier) {
    DraftBar(
      session = shown,
      draft = draft,
      agentStatus = relay?.agent?.status?.takeIf { it.isNotBlank() } ?: shown.agent?.status,
      lastAgentMessage =
        relay?.messages?.lastOrNull { it.fromAgent }?.text
          ?: shown.conversation.lastOrNull { it.fromAgent }?.text,
      showDraft = showDraft && dashboard != null,
      problem = problem,
      busy = busy,
      writeBlockedReason = writeBlockedReason,
      target = target,
      onClearTarget = { target = null },
      onToggle = { showDraft = !showDraft },
      onApply = {
        val chosen = draft ?: return@DraftBar
        run {
          session = edits.apply(shown.id, chosen.id)
          onApplied()
        }
      },
      onUndo = {
        run {
          session = edits.undo(shown.id)
          onApplied()
        }
      },
      onClose = {
        session = null
        watchedId = null
      },
      onSend = { text ->
        val pinned = target
        target = null
        run {
          if (live != null) live.comment(shown.id, text, pinned)
          else session = edits.note(shown.id, text, pinned)
        }
      },
      onDetails = { onOpenSession(shown.id) },
    )
    if (showDraft && dashboard != null && draft != null && !shown.isApplied) {
      DashboardContent(
        dashboard,
        "agent-draft-${shown.id}-${draft.id}",
        snapshot,
        Modifier.weight(1f),
      ) { key, card ->
        Box {
          renderCard(key, card)
          if (shown.isOpen)
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
              shape = MaterialTheme.shapes.small,
              modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
              TextButton(onClick = { target = cardLabel(key, card) }) { Text("Comment") }
            }
        }
      }
    } else current(Modifier.weight(1f))
  }
}

@Composable
private fun DraftBar(
  session: AgentEditSession,
  draft: AgentEditSession.Draft?,
  agentStatus: String?,
  lastAgentMessage: String?,
  showDraft: Boolean,
  problem: String?,
  busy: Boolean,
  writeBlockedReason: String?,
  target: String?,
  onClearTarget: () -> Unit,
  onToggle: () -> Unit,
  onApply: () -> Unit,
  onUndo: () -> Unit,
  onClose: () -> Unit,
  onSend: (String) -> Unit,
  onDetails: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        when {
          session.isApplied -> "Your dashboard uses the agent's draft."
          draft == null ->
            "Your agent is working on: ${session.request.ifBlank { "this dashboard" }}"
          showDraft -> "Agent draft: ${draft.summary.ifBlank { session.request }}"
          else -> "Showing your dashboard as it is. The agent has a draft."
        },
        style = MaterialTheme.typography.titleSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      val said = listOfNotNull(agentStatus?.takeIf { it.isNotBlank() }, lastAgentMessage)
      if (said.isNotEmpty() && session.isOpen)
        Text(
          said.joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      problem?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      writeBlockedReason?.let {
        Text(
          "$it Drafts can be reviewed but not applied.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      val canWrite = !busy && writeBlockedReason == null
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        when {
          session.isApplied -> {
            OutlinedButton(enabled = canWrite, onClick = onUndo) { Text("Undo") }
            TextButton(onClick = onClose) { Text("Close") }
          }
          draft != null -> {
            Button(enabled = canWrite, onClick = onApply) { Text("Use it") }
            OutlinedButton(onClick = onToggle) {
              Text(if (showDraft) "Show current" else "Show draft")
            }
          }
        }
        TextButton(onClick = onDetails) { Text("Details") }
      }
      if (session.isOpen) {
        var text by remember(session.id) { mutableStateOf("") }
        target?.let { AssistChip(onClick = onClearTarget, label = { Text("About $it  ✕") }) }
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Message your agent") },
            singleLine = true,
            modifier = Modifier.weight(1f).widthIn(max = 600.dp),
          )
          Button(
            enabled = !busy && text.isNotBlank(),
            onClick = {
              onSend(text.trim())
              text = ""
            },
          ) {
            Text("Send")
          }
        }
      }
    }
  }
}

/** How often the dashboard looks for a session started on another device. */
private const val OPEN_POLL_MILLIS = 30_000L
