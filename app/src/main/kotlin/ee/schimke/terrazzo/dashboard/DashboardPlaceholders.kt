package ee.schimke.terrazzo.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.dashboard.PlaceholderCard
import ee.schimke.ha.client.dashboard.PlaceholderCardPrompt
import ee.schimke.terrazzo.core.session.HaSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A dashboard's planned (placeholder) cards. A new one starts from a long press on a section
 * heading, which sets [planning]: the editor opens here, above the planned cards, and
 * [onPlanningDone] runs once it saves or is dismissed. Planning stays local even when the dashboard
 * is in read-only mode.
 */
@Composable
internal fun DashboardPlaceholders(
  session: HaSession,
  urlPath: String?,
  planning: Boolean,
  onPlanningDone: () -> Unit,
) {
  val context = LocalContext.current
  val store = remember(context) { PlaceholderCardStore.of(context) }
  val scope = rememberCoroutineScope()
  var cards by
    remember(session.baseUrl, urlPath) { mutableStateOf<List<PlaceholderCard>>(emptyList()) }
  var loaded by remember(session.baseUrl, urlPath) { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  var editing by remember { mutableStateOf<PlaceholderCard?>(null) }

  LaunchedEffect(session.baseUrl, urlPath) {
    try {
      cards = store.read(session.baseUrl, urlPath)
      loaded = true
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      message = "Couldn't load planned cards: ${failure.message}"
    }
  }

  fun save(next: List<PlaceholderCard>) {
    busy = true
    scope.launch {
      try {
        store.write(session.baseUrl, urlPath, next)
        cards = next
        editing = null
        onPlanningDone()
        message = "Saved on this phone. Home Assistant is unchanged."
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        message = "Couldn't save planned cards: ${failure.message}"
      } finally {
        busy = false
      }
    }
  }

  val editorOpen = planning || editing != null
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (editorOpen) {
      PlaceholderCardEditor(
        initial = editing,
        // Saving rewrites the dashboard's whole list, so wait for the stored cards first.
        busy = busy || !loaded,
        onDismiss = {
          if (!busy) {
            editing = null
            onPlanningDone()
          }
        },
        onSave = { updated ->
          save(
            if (cards.any { it.id == updated.id })
              cards.map { if (it.id == updated.id) updated else it }
            else cards + updated
          )
        },
      )
    }
    cards.forEach { card ->
      PlaceholderCardView(
        card = card,
        enabled = !busy && !editorOpen,
        onEdit = { editing = card },
        onRemove = { save(cards.filterNot { it.id == card.id }) },
        onCopyPrompt = {
          busy = true
          scope.launch {
            try {
              val config =
                session.fetchDashboardConfig(urlPath)
                  ?: error("This session can't read dashboard config from Home Assistant.")
              val states = session.loadDashboard(urlPath).second.states.values
              val prompt = PlaceholderCardPrompt.build(card, config, states)
              val clipboard =
                context.getSystemService(ClipboardManager::class.java)
                  ?: error("Clipboard is unavailable.")
              clipboard.setPrimaryClip(ClipData.newPlainText("Card implementation prompt", prompt))
              message =
                "Prompt copied with dashboard config and entity names and states. Paste it into your coding agent."
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (failure: Exception) {
              message = "Couldn't copy prompt: ${failure.message}"
            } finally {
              busy = false
            }
          }
        },
      )
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  }
}

@Composable
internal fun PlaceholderCardView(
  card: PlaceholderCard,
  enabled: Boolean,
  onEdit: () -> Unit,
  onRemove: () -> Unit,
  onCopyPrompt: () -> Unit,
) {
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Planned card · Local draft", style = MaterialTheme.typography.labelMedium)
      Text(card.title, style = MaterialTheme.typography.titleMedium)
      Text(card.request, style = MaterialTheme.typography.bodyMedium)
      if (card.entityIds.isNotEmpty())
        Text(card.entityIds.joinToString(), style = MaterialTheme.typography.bodySmall)
      if (card.componentBindings.isNotBlank())
        Text(card.componentBindings, style = MaterialTheme.typography.bodySmall)
      Text(
        "Awaiting implementation · No live data connected",
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        "Copying includes dashboard config and entity names and states.",
        style = MaterialTheme.typography.bodySmall,
      )
      FilledTonalButton(enabled = enabled, onClick = onCopyPrompt) {
        Text("Copy implementation prompt")
      }
      Row {
        TextButton(enabled = enabled, onClick = onEdit) { Text("Edit") }
        TextButton(enabled = enabled, onClick = onRemove) { Text("Remove") }
      }
    }
  }
}

@Composable
internal fun PlaceholderCardEditor(
  initial: PlaceholderCard?,
  busy: Boolean,
  onDismiss: () -> Unit,
  onSave: (PlaceholderCard) -> Unit,
) {
  var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
  var request by remember(initial) { mutableStateOf(initial?.request.orEmpty()) }
  var entities by remember(initial) { mutableStateOf(initial?.entityIds?.joinToString().orEmpty()) }
  var bindings by remember(initial) { mutableStateOf(initial?.componentBindings.orEmpty()) }
  var error by remember { mutableStateOf<String?>(null) }
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        if (initial == null) "Plan a card" else "Edit planned card",
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        "Describe the card now. An agent can implement its HA data and Remote Compose bindings later. Saved only on this phone."
      )
      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Card title") },
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = request,
        onValueChange = { request = it },
        label = { Text("What should it show?") },
        placeholder = { Text("Garden soil moisture with a trend and low-water warning") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = entities,
        onValueChange = { entities = it },
        label = { Text("HA entity IDs (optional)") },
        placeholder = { Text("sensor.garden_moisture") },
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = bindings,
        onValueChange = { bindings = it },
        label = { Text("Component and bindings (optional)") },
        placeholder = { Text("Gauge: moisture → value, unit → label") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
      )
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      Row {
        TextButton(
          enabled = !busy && title.isNotBlank() && request.isNotBlank(),
          onClick = {
            try {
              onSave(
                PlaceholderCard(
                  initial?.id ?: UUID.randomUUID().toString(),
                  title.trim(),
                  request.trim(),
                  PlaceholderCard.parseEntityIds(entities),
                  bindings.trim(),
                )
              )
            } catch (failure: IllegalArgumentException) {
              error = failure.message
            }
          },
        ) {
          Text("Save placeholder")
        }
        TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
      }
    }
  }
}
