package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.agent.AgentRelayMessage

/*
 * The agent chat's transcript (docs/architecture/dashboard-editing.md#built-in-agent): the person's
 * messages, and the agent's turns, each the steps it took (the tools it called, relayed from Home
 * Assistant) and then its reply. Kept free of the app's other screens so it can move with the agent
 * session flows into a shared module.
 */

/** One turn of the agent: the [steps] it took, then its [reply] (null while it's still working). */
internal data class AgentTurn(val steps: List<AgentRelayMessage>, val reply: AgentRelayMessage?)

/** A line of the transcript: something said (by the person, or a decision or summary) or a turn. */
internal sealed interface ChatItem {
  data class Said(val message: AgentRelayMessage) : ChatItem

  data class Turn(val turn: AgentTurn) : ChatItem
}

/**
 * [messages] (in sequence order) as transcript lines: the agent's steps up to its next message are
 * one turn with that message as its reply. Steps that something else interrupts end a turn with no
 * reply; trailing steps are the turn in progress.
 */
internal fun chatItems(messages: List<AgentRelayMessage>): List<ChatItem> {
  val items = mutableListOf<ChatItem>()
  var steps = mutableListOf<AgentRelayMessage>()
  fun endTurn(reply: AgentRelayMessage?) {
    if (steps.isNotEmpty() || reply != null) items += ChatItem.Turn(AgentTurn(steps, reply))
    steps = mutableListOf()
  }
  for (m in messages) {
    when {
      m.isStep -> steps += m
      m.fromAgent && m.kind == "message" -> endTurn(m)
      else -> {
        if (steps.isNotEmpty()) endTurn(null)
        items += ChatItem.Said(m)
      }
    }
  }
  if (steps.isNotEmpty()) endTurn(null)
  return items
}

/** A tool's name as a person reads it: `find_entities` is "Find entities". */
internal fun stepTitle(step: AgentRelayMessage): String =
  step.tool.orEmpty().replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Step" }

/**
 * One agent turn. Drawn with agent-render where it's published (Android, desktop), whose steps fold
 * away under a summary once the reply arrives; elsewhere (the Wasm panel) with [SharedAgentTurn].
 */
@Composable internal expect fun AgentTurnView(turn: AgentTurn, agentName: String)

/** A message bubble: the person's on the right, everyone else's on the left. */
@Composable
internal fun ChatBubble(label: String, text: String, mine: Boolean) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      color =
        if (mine) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLowest,
      shape = MaterialTheme.shapes.small,
      modifier =
        Modifier.widthIn(max = 520.dp)
          .padding(start = if (mine) 32.dp else 0.dp, end = if (mine) 0.dp else 32.dp),
    ) {
      Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
          label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
      }
    }
  }
}

/**
 * An agent turn drawn with the app's own components: its steps as compact rows (folded under "N
 * steps" once the reply has come), then the reply as a bubble.
 */
@Composable
internal fun SharedAgentTurn(turn: AgentTurn, agentName: String) {
  var open by remember(turn.reply?.seq) { mutableStateOf(turn.reply == null) }
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    if (turn.steps.isNotEmpty() && turn.reply != null)
      TextButton(onClick = { open = !open }) {
        val n = turn.steps.size
        Text(
          "$n step${if (n == 1) "" else "s"} ${if (open) "▾" else "▸"}",
          style = MaterialTheme.typography.labelMedium,
        )
      }
    if (open) turn.steps.forEach { StepRow(it) }
    turn.reply?.let { ChatBubble(agentName.ifBlank { "Agent" }, it.text, mine = false) }
  }
}

@Composable
private fun StepRow(step: AgentRelayMessage) {
  val colors = MaterialTheme.colorScheme
  Column(Modifier.padding(start = 8.dp)) {
    Text(
      (if (step.ok) "✓ " else "✕ ") +
        stepTitle(step) +
        (step.arguments?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
      style = MaterialTheme.typography.labelMedium,
      color = if (step.ok) colors.onSurfaceVariant else colors.error,
    )
    if (step.text.isNotBlank())
      Text(
        step.text,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        maxLines = 2,
      )
  }
}
