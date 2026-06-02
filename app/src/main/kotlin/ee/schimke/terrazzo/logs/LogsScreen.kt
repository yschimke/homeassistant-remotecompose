package ee.schimke.terrazzo.logs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.logs.LogConnectionStatus
import ee.schimke.terrazzo.core.logs.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug surface for the [ee.schimke.terrazzo.core.logs.LogStore] buffer. Four sections,
 * reverse-chronological inside each:
 *
 * - **Crashes** — uncaught exceptions (and caught coroutine failures) captured by the crash
 *   plumbing. Recorded regardless of this screen's preference; tap a row to expand the full stack
 *   trace.
 * - **Data updates** — entity state changes seen on the WebSocket, filtered to entities referenced
 *   by the currently-rendered dashboard so background entities don't drown the view. Kept for 5
 *   minutes.
 * - **Connection** — connect / disconnect / error transitions on the live HA session.
 * - **Actions** — local taps that fan out to `call_service` / `homeassistant.toggle`. Logged before
 *   the network round-trip.
 *
 * Hidden behind `PreferencesStore.logsViewEnabled` — the overflow menu doesn't show the entry until
 * the user flips the toggle in Settings.
 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
  val store = LocalTerrazzoGraph.current.logStore
  val entries by store.flow.collectAsState()

  val crashes = entries.filterIsInstance<LogEntry.Crash>().sortedByDescending { it.timestamp }
  val dataUpdates =
    entries.filterIsInstance<LogEntry.DataUpdate>().sortedByDescending { it.timestamp }
  val connections =
    entries.filterIsInstance<LogEntry.Connection>().sortedByDescending { it.timestamp }
  val actions = entries.filterIsInstance<LogEntry.LocalAction>().sortedByDescending { it.timestamp }

  LogsContent(
    crashes = crashes,
    connections = connections,
    actions = actions,
    dataUpdates = dataUpdates,
    onClear = { store.clear() },
    onBack = onBack,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogsContent(
  crashes: List<LogEntry.Crash>,
  connections: List<LogEntry.Connection>,
  actions: List<LogEntry.LocalAction>,
  dataUpdates: List<LogEntry.DataUpdate>,
  onClear: () -> Unit,
  onBack: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Logs") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = { TextButton(onClick = onClear) { Text("Clear") } },
      )
    },
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      section("Crashes (${crashes.size})") {
        if (crashes.isEmpty()) emptyHint("No crashes recorded. 🎉")
        else crashes.forEach { CrashRow(it) }
      }
      section("Connection (${connections.size})") {
        if (connections.isEmpty()) emptyHint("No connection events yet.")
        else connections.forEach { ConnectionRow(it) }
      }
      section("Local actions (${actions.size})") {
        if (actions.isEmpty()) emptyHint("Tap a dashboard button to see it here.")
        else actions.forEach { ActionRow(it) }
      }
      section("Data updates · last 5 min (${dataUpdates.size})") {
        if (dataUpdates.isEmpty()) {
          emptyHint(
            "No dashboard-entity updates yet. Only entities rendered on " +
              "the current dashboard are tracked."
          )
        } else {
          dataUpdates.forEach { DataUpdateRow(it) }
        }
      }
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
  title: String,
  content: @Composable () -> Unit,
) {
  item(key = "header:$title") {
    Column {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
      )
      HorizontalDivider()
    }
  }
  item(key = "body:$title") { Column { content() } }
}

@Composable
private fun emptyHint(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(vertical = 8.dp),
  )
}

@Composable
private fun ConnectionRow(entry: LogEntry.Connection) {
  LogRow(
    timestamp = entry.timestamp,
    leading = { StatusChip(entry.status) },
    primary = entry.status.label,
    secondary = entry.message,
  )
}

@Composable
private fun CrashRow(entry: LogEntry.Crash) {
  var expanded by remember { mutableStateOf(false) }
  Column(
    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = formatTime(entry.timestamp),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      CrashChip(fatal = entry.fatal)
      Column(modifier = Modifier.weight(1f)) {
        Text(entry.summary, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD32F2F))
        Text(
          "thread: ${entry.threadName}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (expanded) {
      Text(
        text = entry.stackTrace,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@Composable
private fun CrashChip(fatal: Boolean) {
  val color = if (fatal) Color(0xFFD32F2F) else Color(0xFFF57C00)
  AssistChip(
    onClick = {},
    enabled = false,
    label = { Text(if (fatal) "FATAL" else "CAUGHT", style = MaterialTheme.typography.labelSmall) },
    colors =
      AssistChipDefaults.assistChipColors(
        disabledContainerColor = color.copy(alpha = 0.16f),
        disabledLabelColor = color,
      ),
  )
}

@Composable
private fun ActionRow(entry: LogEntry.LocalAction) {
  LogRow(
    timestamp = entry.timestamp,
    leading = null,
    primary = entry.summary,
    secondary = entry.entityId,
  )
}

@Composable
private fun DataUpdateRow(entry: LogEntry.DataUpdate) {
  LogRow(
    timestamp = entry.timestamp,
    leading = null,
    primary = entry.entityId,
    secondary = "${entry.fromState} → ${entry.toState}",
  )
}

@Composable
private fun LogRow(
  timestamp: Long,
  leading: (@Composable () -> Unit)?,
  primary: String,
  secondary: String?,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = formatTime(timestamp),
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (leading != null) leading()
    Column(modifier = Modifier.weight(1f)) {
      Text(primary, style = MaterialTheme.typography.bodyMedium)
      if (!secondary.isNullOrBlank()) {
        Text(
          secondary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun StatusChip(status: LogConnectionStatus) {
  val color =
    when (status) {
      LogConnectionStatus.Connected -> Color(0xFF1976D2)
      LogConnectionStatus.Connecting -> Color(0xFF2E7D32)
      LogConnectionStatus.Disconnected -> Color(0xFF757575)
      LogConnectionStatus.Error -> Color(0xFFD32F2F)
    }
  AssistChip(
    onClick = {},
    enabled = false,
    label = { Text(status.label, style = MaterialTheme.typography.labelSmall) },
    colors =
      AssistChipDefaults.assistChipColors(
        disabledContainerColor = color.copy(alpha = 0.16f),
        disabledLabelColor = color,
      ),
  )
}

private val LogConnectionStatus.label: String
  get() =
    when (this) {
      LogConnectionStatus.Connected -> "Connected"
      LogConnectionStatus.Connecting -> "Connecting"
      LogConnectionStatus.Disconnected -> "Disconnected"
      LogConnectionStatus.Error -> "Error"
    }

private val timeFormat by lazy { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

private fun formatTime(ts: Long): String = timeFormat.format(Date(ts))
