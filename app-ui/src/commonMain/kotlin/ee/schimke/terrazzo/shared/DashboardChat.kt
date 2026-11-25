package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.client.dashboard.DashboardChatLine
import ee.schimke.ha.client.dashboard.DashboardChatReply
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.View
import ee.schimke.ha.rc.cardEntityIds
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The entities [view]'s cards show, in screen order, that [snapshot] has: what the dashboard chat
 * tells the agent is on the person's screen, so "turn these off" means these.
 */
internal fun viewEntityIds(view: View, snapshot: HaSnapshot): List<String> {
  val cards = listOfNotNull(view.header?.card) + view.cards + view.sections.flatMap { it.cards }
  return cards
    .flatMap { cardEntityIds(it) }
    .distinct()
    .filter { it in snapshot.states }
    .take(DashboardChatReply.MAX_ENTITIES)
}

/** What the person is looking at, as the dashboard chat tells the agent. */
data class ChatScreen(val viewTitle: String? = null, val entityIds: List<String> = emptyList()) {
  companion object {
    /** [view] of a dashboard, with the entities its cards show that [snapshot] has. */
    fun of(view: View?, snapshot: HaSnapshot): ChatScreen =
      ChatScreen(view?.title, view?.let { viewEntityIds(it, snapshot) }.orEmpty())
  }
}

/**
 * One dashboard's chat with Home Assistant's conversation agent (`terrazzo/assist`): what was said,
 * the conversation to carry on, and the edit session its drafts are in. [ask] is the call, so tests
 * can stand in for Home Assistant.
 */
@Stable
class DashboardChatState(
  private val ask:
    suspend (
      text: String,
      view: String?,
      entityIds: List<String>,
      conversationId: String?,
      sessionId: String?,
    ) -> DashboardChatReply,
) {
  val lines = mutableStateListOf<DashboardChatLine>()
  var busy by mutableStateOf(false)
    private set

  var conversationId: String? = null
    private set

  /** The edit session drafts go to: the one shown in place, or the one the agent opened. */
  var sessionId by mutableStateOf<String?>(null)

  /**
   * Sends [text] about [screen]; the agent's reply joins [lines], and [onDraft] hears the session
   * its draft is in.
   */
  suspend fun send(text: String, screen: ChatScreen, onDraft: (String) -> Unit) {
    val said = text.trim()
    if (said.isEmpty() || busy) return
    lines += DashboardChatLine(fromPerson = true, text = said)
    busy = true
    try {
      val reply = ask(said, screen.viewTitle, screen.entityIds, conversationId, sessionId)
      conversationId = reply.conversationId ?: conversationId
      lines += DashboardChatLine(fromPerson = false, text = reply.speech, error = reply.error)
      reply.sessionId?.let {
        sessionId = it
        if (reply.drafted) onDraft(it)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      lines +=
        DashboardChatLine(
          fromPerson = false,
          text = "Couldn't reach Home Assistant: ${e.message ?: "try again"}",
          error = true,
        )
    } finally {
      busy = false
    }
  }
}

/**
 * The dashboard chat over an open dashboard: a button that opens a small panel for talking to
 * [agent] about what is on screen. It switches devices with Assist's tools when the agent has them,
 * and changes the dashboard as a draft drawn in place ([onDraft] names its session).
 */
@Composable
fun DashboardChat(
  agent: DashboardAgent,
  state: DashboardChatState,
  /** What is on screen as the person sends: asked afresh for each message. */
  screen: () -> ChatScreen,
  onDraft: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  if (!open) {
    ExtendedFloatingActionButton(onClick = { open = true }, modifier = modifier) {
      Text("Ask ${agent.displayName}")
    }
    return
  }
  var text by rememberSaveable { mutableStateOf("") }
  Surface(
    modifier = modifier.widthIn(max = 420.dp).fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    tonalElevation = 3.dp,
    shadowElevation = 6.dp,
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          agent.displayName,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { open = false }) {
          Icon(Icons.Filled.Close, contentDescription = "Close the chat")
        }
      }
      val listState = rememberLazyListState()
      LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
      }
      if (state.lines.isEmpty())
        Text(
          chatHint(agent),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      else
        LazyColumn(
          Modifier.heightIn(max = 280.dp),
          state = listState,
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          itemsIndexed(state.lines) { _, line -> ChatLine(line) }
        }
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          text,
          { text = it },
          label = { Text("Message") },
          modifier = Modifier.weight(1f),
          enabled = !state.busy,
          maxLines = 4,
        )
        if (state.busy)
          CircularProgressIndicator(
            Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = "Thinking" },
          )
        else
          IconButton(
            enabled = text.isNotBlank(),
            onClick = {
              val said = text
              text = ""
              scope.launch { state.send(said, screen(), onDraft) }
            },
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
          }
      }
    }
  }
}

internal fun chatHint(agent: DashboardAgent): String =
  if (agent.assist == false)
    "Ask ${agent.displayName} to change this dashboard. To switch devices from here too, turn " +
      "on Assist for it in Home Assistant."
  else "Ask ${agent.displayName} to switch what you see here, or to change this dashboard."

@Composable
private fun ChatLine(line: DashboardChatLine) {
  val colors = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = if (line.fromPerson) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      color =
        when {
          line.error -> colors.errorContainer
          line.fromPerson -> colors.primaryContainer
          else -> colors.surfaceContainerHighest
        },
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.widthIn(max = 320.dp),
    ) {
      Text(
        line.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      )
    }
  }
}

/**
 * A [DashboardChatState] for the dashboard [urlPath], kept while it is open; [key] is what asks
 * (the connection or session), and [ask] the call with the dashboard filled in.
 */
@Composable
fun rememberDashboardChat(
  key: Any,
  urlPath: String?,
  ask:
    suspend (
      text: String,
      urlPath: String?,
      view: String?,
      entityIds: List<String>,
      conversationId: String?,
      sessionId: String?,
    ) -> DashboardChatReply,
): DashboardChatState =
  remember(key, urlPath) {
    DashboardChatState { text, view, entityIds, conversationId, sessionId ->
      ask(text, urlPath, view, entityIds, conversationId, sessionId)
    }
  }
