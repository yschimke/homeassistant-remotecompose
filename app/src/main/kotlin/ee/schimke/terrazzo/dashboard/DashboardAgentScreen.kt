package ee.schimke.terrazzo.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.dashboard.DashboardAgentPrompt
import ee.schimke.terrazzo.core.session.HaSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Edit a dashboard with the user's own agent. harc builds a self-contained prompt (the dashboard's
 * current config and the home's entities) for the user to paste into any agent, then takes the
 * agent's answer back, confirms, and saves it to Home Assistant, which stays the source of truth.
 * The replaced config is kept for one undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardAgentScreen(
  session: HaSession,
  urlPath: String?,
  title: String?,
  onBack: () -> Unit,
  onSaved: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbars = remember { SnackbarHostState() }
  var request by remember { mutableStateOf("") }
  var answer by remember { mutableStateOf("") }
  var pending by remember { mutableStateOf<JsonObject?>(null) }
  var busy by remember { mutableStateOf(false) }

  fun report(message: String) = scope.launch { snackbars.showSnackbar(message) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Edit with an agent") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbars) },
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .consumeWindowInsets(padding)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        "Describe the change, copy the prompt into your agent, then paste its answer here. " +
          "The prompt includes this dashboard and your entity names and states.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = request,
        onValueChange = { request = it },
        label = { Text("What should change?") },
        placeholder = { Text("Add a garden section with soil moisture charts") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
      )
      FilledTonalButton(
        enabled = !busy,
        onClick = {
          busy = true
          scope.launch {
            runCatching {
              val config =
                session.fetchDashboardConfig(urlPath)
                  ?: error("This session can't read dashboards from Home Assistant.")
              val states = session.loadDashboard(urlPath).second.states.values
              DashboardAgentPrompt.build(request, config, states, title)
            }
              .onSuccess {
                copyToClipboard(context, it)
                report("Prompt copied. Paste it into your agent.")
              }
              .onFailure { report(it.message ?: "Couldn't build the prompt.") }
            busy = false
          }
        },
      ) {
        Text("Copy prompt")
      }
      OutlinedTextField(
        value = answer,
        onValueChange = { answer = it },
        label = { Text("Agent's answer") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
      )
      Button(
        enabled = !busy && answer.isNotBlank(),
        onClick = {
          runCatching { DashboardAgentPrompt.parseAnswer(answer) }
            .onSuccess { pending = it }
            .onFailure { report(it.message ?: "That answer isn't a dashboard.") }
        },
      ) {
        Text("Save to Home Assistant")
      }
    }
  }

  pending?.let { config ->
    val views = (config["views"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
    AlertDialog(
      onDismissRequest = { pending = null },
      title = { Text("Replace this dashboard?") },
      text = {
        Text(
          "Home Assistant's copy of ${title ?: "this dashboard"} will be replaced with the agent's " +
            "version ($views ${if (views == 1) "view" else "views"}). You can undo once."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            pending = null
            busy = true
            scope.launch {
              val saved = runCatching { session.replaceDashboardConfig(urlPath, config) }
              busy = false
              saved
                .onSuccess { previous ->
                  onSaved()
                  val result = snackbars.showSnackbar("Dashboard saved.", actionLabel = "Undo")
                  if (result == SnackbarResult.ActionPerformed) {
                    runCatching { session.replaceDashboardConfig(urlPath, previous) }
                      .onSuccess { onSaved() }
                      .onFailure { report(it.message ?: "Couldn't undo.") }
                  }
                }
                .onFailure { report(it.message ?: "Home Assistant refused the dashboard.") }
            }
          }
        ) {
          Text("Replace")
        }
      },
      dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
    )
  }
}

private fun copyToClipboard(context: Context, text: String) {
  val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
  clipboard.setPrimaryClip(ClipData.newPlainText("Dashboard prompt", text))
}
