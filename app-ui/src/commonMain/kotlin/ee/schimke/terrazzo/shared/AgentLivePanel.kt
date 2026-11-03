package ee.schimke.terrazzo.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.agent.AgentRelayMessage
import ee.schimke.ha.client.agent.AgentRelayState

/**
 * The live half of an agent session, shared by Card Studio and dashboard edits on every host
 * (Android, desktop, Wasm, Wasmo): who is connected and what they're doing, the code that lets an
 * agent in, and the conversation. Drafts themselves are shown by the screen that owns them.
 *
 * [target] is the card a message will be about (picked on the draft), cleared by [onClearTarget].
 */
@Composable
fun AgentLivePanel(
  state: AgentRelayState?,
  busy: Boolean,
  onCopyInvite: (code: String) -> Unit,
  onNewCode: () -> Unit,
  onClaim: (code: String) -> Unit,
  onSend: (text: String) -> Unit,
  modifier: Modifier = Modifier,
  target: String? = null,
  onClearTarget: () -> Unit = {},
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (state == null) {
        Text("Connecting to the design server…", style = MaterialTheme.typography.bodyMedium)
        return@Column
      }
      Presence(state)
      val code = state.joinCode
      if (!state.agent.joined || code != null) {
        JoinCode(code, state.joinCodeExpiresInSeconds, busy, onCopyInvite, onNewCode)
        AgentCode(busy, onClaim)
      }
      Conversation(state.messages)
      if (state.agent.joined) Composer(busy, target, onClearTarget, onSend)
    }
  }
}

@Composable
private fun Presence(state: AgentRelayState) {
  val agent = state.agent
  val colors = MaterialTheme.colorScheme
  val (dot, line) =
    when {
      state.decision == "accepted" -> colors.primary to "You accepted the draft."
      agent.connected ->
        colors.primary to
          (agent.name +
            (agent.status.takeIf { it.isNotBlank() }?.let { " · $it" } ?: " is connected"))
      agent.joined ->
        colors.outline to
          "${agent.name} was here ${ago(agent.lastSeenSecondsAgo)}. It picks up your messages when it's back."
      else -> colors.outline to "Waiting for an agent to join"
    }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    Box(Modifier.size(10.dp).background(dot, CircleShape))
    Text(line, style = MaterialTheme.typography.titleSmall)
  }
}

private fun ago(seconds: Long?): String =
  when {
    seconds == null -> "earlier"
    seconds < 90 -> "a moment ago"
    seconds < 3600 -> "${seconds / 60} min ago"
    else -> "${seconds / 3600} h ago"
  }

@Composable
private fun JoinCode(
  code: String?,
  expiresIn: Long,
  busy: Boolean,
  onCopyInvite: (String) -> Unit,
  onNewCode: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (code == null) {
      Text(
        "The last code was used or expired.",
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedButton(onClick = onNewCode, enabled = !busy) { Text("New code") }
      return@Column
    }
    Text("Give your agent this code", style = MaterialTheme.typography.bodyMedium)
    SelectionContainer {
      Text(
        code,
        style = MaterialTheme.typography.headlineMedium,
        fontFamily = FontFamily.Monospace,
      )
    }
    Text(
      "It works once, for ${(expiresIn / 60).coerceAtLeast(1)} more min. The agent never gets " +
        "your Home Assistant login and can only suggest drafts.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onCopyInvite(code) }, enabled = !busy) {
        Text("Copy invite for the agent")
      }
      TextButton(onClick = onNewCode, enabled = !busy) { Text("New code") }
    }
  }
}

/** The other direction: the agent asked first (`request_access`) and showed the person a code. */
@Composable
private fun AgentCode(busy: Boolean, onClaim: (String) -> Unit) {
  var open by remember { mutableStateOf(false) }
  var code by remember { mutableStateOf("") }
  if (!open) {
    TextButton(onClick = { open = true }) { Text("Have a code from your agent?") }
    return
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
      value = code,
      onValueChange = { code = it.take(12) },
      label = { Text("Code from your agent") },
      singleLine = true,
      keyboardOptions =
        KeyboardOptions(
          capitalization = KeyboardCapitalization.Characters,
          imeAction = ImeAction.Done,
        ),
      keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onClaim(code) }),
      modifier = Modifier.widthIn(max = 220.dp),
    )
    OutlinedButton(onClick = { onClaim(code) }, enabled = !busy && code.isNotBlank()) {
      Text("Connect")
    }
  }
}

@Composable
private fun Conversation(messages: List<AgentRelayMessage>) {
  if (messages.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    messages.takeLast(8).forEach { m ->
      val mine = !m.fromAgent
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
              when {
                m.kind == "decision" -> "You decided"
                m.kind == "summary" -> "Draft"
                mine -> "You" + (m.target?.let { " · about $it" } ?: "")
                else -> "Agent"
              },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(m.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
          }
        }
      }
    }
  }
}

@Composable
private fun Composer(
  busy: Boolean,
  target: String?,
  onClearTarget: () -> Unit,
  onSend: (String) -> Unit,
) {
  var text by remember { mutableStateOf("") }
  fun send() {
    if (text.isBlank()) return
    onSend(text)
    text = ""
  }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    target?.let { AssistChip(onClick = onClearTarget, label = { Text("About $it  ✕") }) }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(2000) },
        label = { Text("Tell the agent what to change") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        modifier = Modifier.weight(1f),
        maxLines = 4,
      )
      Button(onClick = { send() }, enabled = !busy && text.isNotBlank()) { Text("Send") }
    }
  }
}
