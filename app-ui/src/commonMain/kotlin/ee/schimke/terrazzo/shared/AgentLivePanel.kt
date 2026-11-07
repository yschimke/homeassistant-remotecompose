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
import androidx.compose.runtime.collectAsState
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
import ee.schimke.ha.client.agent.AgentRelayClient
import ee.schimke.ha.client.agent.AgentRelayMessage
import ee.schimke.ha.client.agent.AgentRelayState
import kotlinx.coroutines.launch

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
  /**
   * The join code and "Have a code from your agent?": off where the screen hands agents a prompt
   * instead (a dashboard session's Copy prompt), so there's one way in.
   */
  showCodes: Boolean = true,
  /** Let the person write before an agent has joined: it reads the conversation when it does. */
  composeBeforeJoin: Boolean = false,
  /**
   * A short window (landscape with the keyboard up): the field shows two lines at most and scrolls
   * within itself, so all of it fits above the keyboard.
   */
  compact: Boolean = false,
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
      if (!showCodes) {
        // The screen offers its own way in.
      } else if (code != null || (!state.agent.joined && !state.linked)) {
        JoinCode(code, state.joinCodeExpiresInSeconds, busy, onCopyInvite, onNewCode)
        AgentCode(busy, onClaim)
      } else if (state.linked && !state.agent.joined) {
        // Sent to the connected agent: nothing to hand over, unless the person wants another one.
        TextButton(onClick = onNewCode, enabled = !busy) { Text("Use another agent instead") }
      }
      if (composeBeforeJoin && !state.agent.joined)
        Text(
          "Comment now: your agent starts with your comments when it joins.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      Conversation(state.messages)
      if (state.agent.joined || composeBeforeJoin)
        Composer(busy, target, onClearTarget, onSend, maxLines = if (compact) 2 else 4)
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
      state.linked ->
        colors.outline to "Sent to ${agent.name}. It picks this up when it next listens."
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
  maxLines: Int = 4,
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
        maxLines = maxLines,
      )
      Button(onClick = { send() }, enabled = !busy && text.isNotBlank()) { Text("Send") }
    }
  }
}

/** The person's agent links, one per design server, kept on this device ([readAgentGrants]). */
internal object AgentLinkStore {
  private const val KEY = "terrazzo.agentLinks"
  private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
  private var memory = emptyMap<String, String>()

  /** Bumped on every [put], so [rememberAgentConnected] reads the links again. */
  val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

  private fun all(): Map<String, String> =
    readAgentGrants(KEY)?.let {
      runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
    } ?: memory

  fun get(server: String): String? = keyOf(server)?.let { all()[it] }

  fun put(server: String, link: String?) {
    val key = keyOf(server) ?: return
    val values = if (link == null) all() - key else all() + (key to link)
    memory = values
    writeAgentGrants(KEY, if (values.isEmpty()) null else json.encodeToString(values))
    // A new link waits for an agent to claim its setup code; a removed one has nobody.
    writeConnected(key, if (link == null) null else false)
    changes.value++
  }

  // Whether an agent has claimed each link, as the relay last reported it. A link with no entry
  // predates this record and was set up before the dashboard waited on it, so it counts as
  // connected until the relay says otherwise.
  private const val CONNECTED_KEY = "terrazzo.agentLinksConnected"
  private var connectedMemory = emptyMap<String, Boolean>()

  private fun connectedAll(): Map<String, Boolean> =
    readAgentGrants(CONNECTED_KEY)?.let {
      runCatching { json.decodeFromString<Map<String, Boolean>>(it) }.getOrNull()
    } ?: connectedMemory

  private fun writeConnected(key: String, connected: Boolean?) {
    val values =
      if (connected == null) connectedAll() - key else connectedAll() + (key to connected)
    connectedMemory = values
    writeAgentGrants(CONNECTED_KEY, if (values.isEmpty()) null else json.encodeToString(values))
  }

  /** Whether an agent has claimed this person's link on [server]. */
  fun isConnected(server: String): Boolean {
    val key = keyOf(server) ?: return false
    return all()[key] != null && connectedAll()[key] != false
  }

  /** Records the relay's view of whether an agent has claimed the link on [server]. */
  fun setConnected(server: String, connected: Boolean) {
    val key = keyOf(server) ?: return
    if (all()[key] == null || connectedAll()[key] == connected) return
    writeConnected(key, connected)
    changes.value++
  }

  private fun keyOf(server: String) = runCatching {
    ee.schimke.ha.client.design.studioServerUrl(server)
  }
    .getOrNull()
}

/**
 * Whether an AI agent is connected to this person on design [server] ([AgentConnection] set one
 * up): what the dashboard's "Ask your agent" and "Plan a card" wait for. Follows connecting and
 * disconnecting while the app runs.
 */
@Composable
fun rememberAgentConnected(server: String): Boolean {
  val changes by AgentLinkStore.changes.collectAsState()
  return remember(server, changes) { AgentLinkStore.isConnected(server) }
}

/**
 * The setup phase: connect an AI agent to this person once, on [server]. After that every card and
 * dashboard they ask about goes straight to it (Ask your agent, for a card or a dashboard), with
 * nothing to copy. Shows the setup code (or takes the agent's), then who is connected and whether
 * it is listening. [onCopy] puts text on the clipboard or share sheet.
 */
@Composable
fun AgentConnection(server: String, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var link by remember(server) { mutableStateOf(AgentLinkStore.get(server)) }
  var state by
    remember(server, link) { mutableStateOf<ee.schimke.ha.client.agent.AgentLinkState?>(null) }
  var busy by remember { mutableStateOf(false) }
  var problem by remember { mutableStateOf<String?>(null) }

  androidx.compose.runtime.LaunchedEffect(server, link) {
    val current = link ?: return@LaunchedEffect
    val relay =
      runCatching { ee.schimke.ha.client.agent.AgentRelayClient(server) }.getOrNull()
        ?: return@LaunchedEffect
    try {
      relay.watchLink(current).collect {
        state = it
        AgentLinkStore.setConnected(server, it.connected)
      }
    } catch (e: ee.schimke.ha.client.agent.AgentSessionEndedException) {
      // Disconnected elsewhere, or idle for a month: back to the setup.
      AgentLinkStore.put(server, null)
      link = null
    } finally {
      relay.close()
    }
  }

  fun act(block: suspend (ee.schimke.ha.client.agent.AgentRelayClient) -> Unit) {
    if (busy) return
    busy = true
    problem = null
    scope.launch {
      val relay = ee.schimke.ha.client.agent.AgentRelayClient(server)
      try {
        block(relay)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        problem = e.message ?: "The design server didn't answer."
      } finally {
        relay.close()
        busy = false
      }
    }
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      val current = state
      when {
        link == null -> {
          Text("Your AI agent", style = MaterialTheme.typography.titleSmall)
          Text(
            "Connect your agent once. After that, every card or dashboard you ask about goes " +
              "straight to it, and you talk to it here: nothing to copy.",
            style = MaterialTheme.typography.bodySmall,
          )
          Button(
            onClick = {
              act { relay ->
                val (created, _) = relay.createLink()
                AgentLinkStore.put(server, created)
                link = created
              }
            },
            enabled = !busy,
          ) {
            Text("Connect an agent")
          }
        }
        current == null -> Text("Connecting to the design server…")
        !current.connected -> {
          Text("Connect your agent", style = MaterialTheme.typography.titleSmall)
          JoinCode(
            current.code,
            current.codeExpiresInSeconds,
            busy,
            onCopyInvite = { code -> onCopy(AgentRelayClient.setupInvite(server, code)) },
            onNewCode = { act { it.newLinkCode(requireNotNull(link)) } },
          )
          AgentCode(busy) { code -> act { it.claimLink(requireNotNull(link), code) } }
          TextButton(
            onClick = {
              act { relay ->
                relay.disconnect(requireNotNull(link))
                AgentLinkStore.put(server, null)
                link = null
              }
            },
            enabled = !busy,
          ) {
            Text("Cancel")
          }
        }
        else -> {
          val colors = MaterialTheme.colorScheme
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          ) {
            Box(
              Modifier.size(10.dp)
                .background(if (current.listening) colors.primary else colors.outline, CircleShape),
            )
            Text(
              if (current.listening) "${current.name} is connected and listening"
              else
                "${current.name} is connected. It picks up requests when it next listens " +
                  "(last seen ${ago(current.lastSeenSecondsAgo)}).",
              style = MaterialTheme.typography.titleSmall,
            )
          }
          TextButton(
            onClick = {
              act { relay ->
                relay.disconnect(requireNotNull(link))
                AgentLinkStore.put(server, null)
                link = null
              }
            },
            enabled = !busy,
          ) {
            Text("Disconnect ${current.name}")
          }
        }
      }
      problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }
}
