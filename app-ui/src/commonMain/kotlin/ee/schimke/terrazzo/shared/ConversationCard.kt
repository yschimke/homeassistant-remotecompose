package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.HaClient
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Real, authenticated HA logbook content. No conversation or credential is sent to a resolver. */
@Composable
internal fun ConversationCard(
  card: CardConfig,
  entityId: String,
  snapshot: HaSnapshot,
  client: HaClient,
  onMoreInfo: (String) -> Unit,
) {
  var messages by remember(client, entityId) { mutableStateOf(emptyList<ConversationMessage>()) }
  var status by remember(client, entityId) { mutableStateOf("Loading conversation…") }
  var draft by remember(client, entityId) { mutableStateOf("") }
  var sending by remember(client, entityId) { mutableStateOf(false) }
  var sendStatus by remember(client, entityId) { mutableStateOf<String?>(null) }
  val connection by client.state.collectAsState()
  val scope = rememberCoroutineScope()
  val entity = snapshot.states[entityId]
  val entryId = (card.raw["entry_id"] as? JsonPrimitive)?.contentOrNull
  val companions =
    snapshot.states.keys.count { it.startsWith("sensor.meshcore_") && "_node_status_" in it }
  val recipientKnown = entryId != null || companions == 1
  val request = entity?.let { conversationSend(it, entryId, draft.trim()) }
  LaunchedEffect(client, entityId) {
    client.state.collectLatest { state ->
      if (state != HaClient.ConnectionState.Ready) {
        status = "Offline · waiting for Home Assistant"
        return@collectLatest
      }
      while (true) {
        try {
          messages =
            conversationMessages(
              withTimeoutOrNull(10_000) {
                client.fetchLogbook(entityId, Clock.System.now() - 24.hours)
              } ?: error("History timeout"),
              entityId,
            )
          status =
            if (messages.isEmpty()) "No messages in the last 24 hours"
            else "Last 24 hours · refreshes every 15 seconds"
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          status = "Conversation history unavailable · retrying"
        }
        delay(15_000)
      }
    }
  }
  val scroll = rememberScrollState()
  LaunchedEffect(messages) {
    if (scroll.maxValue - scroll.value < 80 || scroll.value == 0) scroll.scrollTo(scroll.maxValue)
  }
  OutlinedCard(Modifier.fillMaxWidth().entityDetails(entityId, onMoreInfo)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        (card.raw["title"] as? JsonPrimitive)?.contentOrNull
          ?: (card.raw["name"] as? JsonPrimitive)?.contentOrNull
          ?: (entity?.attributes?.get("friendly_name") as? JsonPrimitive)?.contentOrNull
          ?: "Conversation",
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        status,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (messages.isNotEmpty()) {
        SelectionContainer {
          Column(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            messages.forEach { message ->
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
              ) {
                Column(
                  Modifier.fillMaxWidth().padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                    message.sender +
                      " · " +
                      Instant.fromEpochMilliseconds((message.timestamp * 1000).toLong())
                        .toString()
                        .substring(11, 16) +
                      " UTC",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Text(message.text, style = MaterialTheme.typography.bodyLarge)
                }
              }
            }
          }
        }
      }
      if (!recipientKnown)
        Text(
          "Choose a companion with entry_id to enable replies.",
          style = MaterialTheme.typography.bodySmall,
        )
      else if (entity == null || conversationSend(entity, entryId, "check") == null)
        Text(
          "Recipient unavailable · open Home Assistant for details.",
          style = MaterialTheme.typography.bodySmall,
        )
      else {
        OutlinedTextField(
          draft,
          { draft = it },
          label = { Text("Message") },
          supportingText = { Text("${draft.encodeToByteArray().size}/120 bytes") },
          isError = draft.encodeToByteArray().size > 120,
          enabled = !sending && connection == HaClient.ConnectionState.Ready,
          modifier = Modifier.fillMaxWidth(),
          minLines = 2,
          maxLines = 4,
        )
        Button(
          enabled = request != null && !sending && connection == HaClient.ConnectionState.Ready,
          onClick = {
            val action = request ?: return@Button
            sending = true
            sendStatus = null
            scope.launch {
              try {
                client.callService("meshcore", action.service, serviceData = action.data)
                draft = ""
                sendStatus = "Accepted by Home Assistant · delivery unconfirmed"
              } catch (e: CancellationException) {
                throw e
              } catch (_: Exception) {
                sendStatus = "Could not send · your draft is kept"
              } finally {
                sending = false
              }
            }
          },
        ) {
          Text(if (sending) "Sending…" else "Send message")
        }
      }
      sendStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      TextButton(onClick = { onMoreInfo(entityId) }) { Text("Open in Home Assistant") }
    }
  }
}
