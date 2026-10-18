@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.terrazzo.shared

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.*

/** Data-independent demo surface; the host supplies its current dashboard and snapshot. */
@Composable
fun DashboardExperience(
  dashboard: Dashboard?,
  snapshot: HaSnapshot?,
  demo: Boolean = true,
  dataStatus: DemoDataStatus,
  onToggle: ((String) -> Unit)? = null,
  controlError: String? = null,
) {
  var detailsEntity by remember(dashboard) { mutableStateOf<String?>(null) }
  var design by rememberSaveable { mutableStateOf("classic") }
  var dark by rememberSaveable { mutableStateOf(false) }
  MaterialTheme(
    colorScheme = if (dark || design == "console") darkColorScheme() else lightColorScheme()
  ) {
    val selectedEntity = detailsEntity
    if (selectedEntity != null && snapshot != null) {
      EntityDetailsDialog(selectedEntity, snapshot) { detailsEntity = null }
    }
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(if (demo) "Demo home" else dashboard?.title ?: "Home Assistant") },
          actions = {
            TextButton(onClick = { dark = !dark }) { Text(if (dark) "Light" else "Dark") }
          },
        )
      }
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        Text(
          if (!demo) "Home Assistant · read only"
          else
            when (dataStatus) {
              DemoDataStatus.LIVE -> "Example home · live demo data · try the switches"
              DemoDataStatus.CONNECTING -> "Connecting to demo Home Assistant…"
              DemoDataStatus.UNAVAILABLE -> "Demo Home Assistant unavailable · retrying"
            },
          Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          style = MaterialTheme.typography.labelLarge,
        )
        controlError?.let {
          Text(
            it,
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.error,
          )
        }
        Row(
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          listOf(
              "classic" to "Classic",
              "braun" to "Braun",
              "lcd" to "LCD",
              "console" to "Console",
            )
            .forEach { (id, label) ->
              FilterChip(
                selected = design == id,
                onClick = { design = id },
                label = { Text(label) },
              )
            }
        }
        // A changed HA dashboard must not retain the previous grid's cards or semantics.
        // State-only refreshes keep the same key, preserving playback and selected views.
        if (dashboard != null && snapshot != null)
          key(dashboard) {
            DashboardContent(
              dashboard,
              if (demo) "demo" else "ha-panel",
              snapshot,
              Modifier.weight(1f),
            ) { _, card ->
              LibraryCard(
                card,
                snapshot,
                design,
                dark || design == "console",
                onToggle,
                onMoreInfo = { detailsEntity = it },
              ) {
                NativeCard(
                  card,
                  snapshot,
                  readOnly = onToggle == null,
                  onMoreInfo = { detailsEntity = it },
                ) {
                  onToggle?.invoke(it)
                }
              }
            }
          }
      }
    }
  }
}
