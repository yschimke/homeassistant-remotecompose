@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.*
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Shared phone/desktop authoring surface; provider credentials are never saved or sent to HA. */
@Composable
fun CardStudioScreen(
  card: CardConfig,
  snapshot: HaSnapshot,
  onBack: () -> Unit,
  initialServerUrl: String = "https://ha.coo.ee",
  preview: @Composable () -> Unit,
) {
  var server by remember { mutableStateOf(initialServerUrl) }
  var provider by remember { mutableStateOf(CardAgentProvider.OpenRouter) }
  var apiKey by remember { mutableStateOf("") }
  var model by remember { mutableStateOf("") }
  var endpoint by remember { mutableStateOf("") }
  var instruction by remember { mutableStateOf("") }
  var draft by remember { mutableStateOf<JsonObject?>(null) }
  var editorUrl by remember { mutableStateOf<String?>(null) }
  var externalUrl by remember(editorUrl) { mutableStateOf<String?>(null) }
  var message by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var job by remember { mutableStateOf<Job?>(null) }
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  val latestSnapshot by rememberUpdatedState(snapshot)
  fun run(generate: Boolean) {
    job = scope.launch {
      busy = true
      message = null
      var studio: CardStudioClient? = null
      var agent: AgentCardDesigner? = null
      try {
        studio = CardStudioClient(server)
        val seed = draft ?: studio.seed(card, latestSnapshot)
        if (generate) {
          agent = AgentCardDesigner()
          draft = agent.design(provider, apiKey, model, instruction, seed, endpoint)
          message = "Draft ready. Open it in UI Builder to review the design."
        } else {
          draft = seed
          editorUrl = studio.handoff(seed)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message =
          if (e is IllegalArgumentException)
            "The draft or connection settings are invalid. Check the HTTPS address and try again."
          else
            "Could not complete the request. Check the server, provider key, model, and network, then try again."
      } finally {
        agent?.close()
        studio?.close()
        busy = false
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Design or fix this card") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
      )
    }
  ) { padding ->
    val url = editorUrl
    if (url != null) {
      Column(Modifier.fillMaxSize().padding(padding)) {
        Row(
          Modifier.fillMaxWidth().padding(8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TextButton(onClick = { editorUrl = null }) { Text("Agent controls") }
          TextButton(onClick = { uriHandler.openUri(externalUrl ?: url) }) {
            Text("Open in browser")
          }
        }
        CardEditorBrowser(url, Modifier.fillMaxWidth().weight(1f)) { externalUrl = it }
      }
    } else {
      Column(
        Modifier.fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Current card", style = MaterialTheme.typography.titleMedium)
        preview()
        OutlinedTextField(
          server,
          {
            server = it
            draft = null
          },
          label = { Text("Design server") },
          singleLine = true,
          enabled = !busy,
          modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { run(false) }, enabled = !busy && server.isNotBlank()) {
          Text(if (draft == null) "Design in UI Builder" else "Preview draft in UI Builder")
        }
        HorizontalDivider()
        Text("Connect an agent", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CardAgentProvider.entries.forEach { option ->
            FilterChip(
              provider == option,
              {
                provider = option
                apiKey = ""
                model = ""
              },
              label = { Text(option.label) },
              enabled = !busy,
            )
          }
        }
        if (provider == CardAgentProvider.Agent) {
          OutlinedTextField(
            endpoint,
            { endpoint = it },
            label = { Text("Agent chat-completions HTTPS endpoint") },
            enabled = !busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        OutlinedTextField(
          apiKey,
          { apiKey = it },
          label = { Text("API key · this session only") },
          enabled = !busy,
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          model,
          { model = it },
          label = { Text("Model ID") },
          enabled = !busy,
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          instruction,
          { instruction = it },
          label = { Text("Create a missing design or describe the issue") },
          placeholder = { Text("Make the state easier to read and fit this card at phone width.") },
          enabled = !busy,
          minLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          "Sending shares the selected card's display text and current values with ${provider.label}. Other entities, Home Assistant tokens and entity-picture URLs stay on this device.",
          style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = { run(true) },
            enabled =
              !busy &&
                apiKey.isNotBlank() &&
                model.isNotBlank() &&
                instruction.isNotBlank() &&
                server.isNotBlank() &&
                (provider != CardAgentProvider.Agent || endpoint.isNotBlank()),
          ) {
            Text(if (busy) "Working…" else "Generate or fix draft")
          }
          if (busy) TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
          if (draft != null && !busy)
            TextButton(
              onClick = {
                draft = null
                message = null
              }
            ) {
              Text("Reset draft")
            }
        }
        message?.let { Text(it) }
        Text(
          "Designs remain drafts. Saving in UI Builder does not change your Home Assistant dashboard.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
internal expect fun CardEditorBrowser(
  url: String,
  modifier: Modifier,
  onNavigation: (String) -> Unit,
)
