package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.dashboard.AgentEditSession
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.client.dashboard.EditScope
import ee.schimke.ha.client.dashboard.openingRequest
import ee.schimke.ha.client.dashboard.scopeLabel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/*
 * With a dashboard agent answering the Terrazzo integration's edit sessions ([DashboardAgent.enabled],
 * one of Home Assistant's own conversation agents), editing a dashboard opens a chat with it in an
 * edit session ([AgentEditScreen]), new dashboards included, which a person then applies; editing
 * by hand stays in the dashboard's menu. docs/architecture/dashboard-editing.md.
 */

/** What the chat's entry points say while a dashboard agent answers. */
const val CHAT_WITH_AGENT = "Chat with your agent"

/**
 * Asks for a new dashboard's title, then starts an edit session building it with [agent]:
 * [onStarted] gets the session, whose `urlPath` is where Home Assistant will create the dashboard
 * once a draft is applied.
 */
@Composable
fun NewDashboardDialog(
  edits: AgentEdits,
  agent: DashboardAgent,
  onDismiss: () -> Unit,
  onStarted: (AgentEditSession) -> Unit,
) {
  var title by remember { mutableStateOf("") }
  var request by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("New dashboard") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          "${agent.displayName} builds it from this home's entities. You see each draft and " +
            "create the dashboard when you like it.",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
          title,
          { title = it },
          label = { Text("Title") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          request,
          { request = it },
          label = { Text("What should it show? (optional)") },
          placeholder = { Text("The garden: soil moisture, the sprinklers and the weather") },
          minLines = 2,
          modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      Button(
        enabled = !busy && title.isNotBlank(),
        onClick = {
          busy = true
          error = null
          scope.launch {
            try {
              val about = EditScope.newDashboard(title.trim())
              val asked = request.trim().ifBlank { about.openingRequest(null) }
              onStarted(edits.start(null, asked, about).session)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              error = agentEditFailure(e)
            } finally {
              busy = false
            }
          }
        },
      ) {
        Text("Start")
      }
    },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * New dashboards with the dashboard agent, for a dashboard list: **New dashboard**, then the ones
 * still being built, to pick back up.
 */
@Composable
fun NewDashboardSessions(
  edits: AgentEdits,
  agent: DashboardAgent,
  onOpen: (AgentEditSession) -> Unit,
  modifier: Modifier = Modifier,
) {
  var asking by remember { mutableStateOf(false) }
  var building by remember(edits) { mutableStateOf<List<AgentEditSession>>(emptyList()) }
  LaunchedEffect(edits) { building = runCatching { edits.newDashboards() }.getOrDefault(building) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { asking = true }) { Text("New dashboard") }
    building.forEach { session ->
      OutlinedButton(onClick = { onOpen(session) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
          Text(session.scopeLabel(null), style = MaterialTheme.typography.titleSmall, maxLines = 1)
          Text(
            "Being built · ${sessionLine(session)}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
          )
        }
      }
    }
  }
  if (asking)
    NewDashboardDialog(
      edits,
      agent,
      onDismiss = { asking = false },
      onStarted = {
        asking = false
        onOpen(it)
      },
    )
}

/**
 * **New dashboard** from a menu: the dashboards still being built to pick back up, if there are
 * any, beside starting another; straight to [NewDashboardDialog] when there are none. Without it a
 * build left before **Create it** would have no way back on a host with no dashboard list.
 */
@Composable
fun NewDashboardChooser(
  edits: AgentEdits,
  agent: DashboardAgent,
  onDismiss: () -> Unit,
  onOpen: (AgentEditSession) -> Unit,
) {
  var building by remember(edits) { mutableStateOf<List<AgentEditSession>?>(null) }
  var asking by remember { mutableStateOf(false) }
  LaunchedEffect(edits) {
    building = runCatching { edits.newDashboards() }.getOrDefault(emptyList())
  }
  val listed = building ?: return
  if (asking || listed.isEmpty()) {
    NewDashboardDialog(edits, agent, onDismiss = onDismiss, onStarted = onOpen)
    return
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New dashboard") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pick up a dashboard you're building, or start another.")
        listed.forEach { session ->
          OutlinedButton(onClick = { onOpen(session) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
              Text(
                session.scopeLabel(null),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
              )
              Text(sessionLine(session), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
          }
        }
      }
    },
    confirmButton = { Button(onClick = { asking = true }) { Text("Start another") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
