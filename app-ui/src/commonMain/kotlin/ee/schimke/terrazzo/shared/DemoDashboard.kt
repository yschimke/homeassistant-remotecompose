@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
  onTemperatureChange: ((Double) -> Unit)? = null,
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
        DemoToolbar(
          title = if (demo) "Demo home" else dashboard?.title ?: "Home Assistant",
          status =
            if (!demo) "Read only"
            else
              when (dataStatus) {
                DemoDataStatus.LIVE -> "Live · try the switches"
                DemoDataStatus.CONNECTING -> "Connecting to demo HA…"
                DemoDataStatus.UNAVAILABLE -> "Demo HA unavailable · retrying"
              },
          design = design,
          onDesignChange = { design = it },
          dark = dark,
          onDarkChange = { dark = it },
        )
      }
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        controlError?.let {
          Text(
            it,
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.error,
          )
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
                onTemperatureChange = onTemperatureChange,
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

/** Keep controls in one app bar even on narrow phones; menus overlay the dashboard. */
@Composable
private fun DemoToolbar(
  title: String,
  status: String,
  design: String,
  onDesignChange: (String) -> Unit,
  dark: Boolean,
  onDarkChange: (Boolean) -> Unit,
) {
  var stylesExpanded by remember { mutableStateOf(false) }
  val styles = rememberCatalogStyles(stylesExpanded)
  fun label(id: String) =
    when (id) {
      "ikea" -> "IKEA Showroom"
      "lcd" -> "LCD"
      else -> id.replaceFirstChar { it.uppercaseChar() }
    }
  var optionsExpanded by remember { mutableStateOf(false) }
  TopAppBar(
    title = {
      Column {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          status,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    },
    actions = {
      Box {
        TextButton(
          onClick = { stylesExpanded = true },
          modifier =
            Modifier.heightIn(min = 48.dp).semantics {
              contentDescription = "Card style: ${label(design)}"
            },
        ) {
          Text(label(design))
          Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = stylesExpanded, onDismissRequest = { stylesExpanded = false }) {
          styles.forEach { id ->
            DropdownMenuItem(
              text = { Text(label(id)) },
              modifier = Modifier.semantics { selected = design == id },
              trailingIcon = { if (design == id) Text("✓") },
              onClick = {
                onDesignChange(id)
                stylesExpanded = false
              },
            )
          }
        }
      }
      Box {
        IconButton(onClick = { optionsExpanded = true }, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Default.MoreVert, contentDescription = "Demo options")
        }
        DropdownMenu(expanded = optionsExpanded, onDismissRequest = { optionsExpanded = false }) {
          DropdownMenuItem(
            text = { Text(if (dark) "Use light theme" else "Use dark theme") },
            onClick = {
              onDarkChange(!dark)
              optionsExpanded = false
            },
          )
        }
      }
    },
  )
}
