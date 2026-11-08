package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.dashboard.DashboardListState

/** The connected shared shell supplies actions to the desktop's native menu. */
class DesktopCommands {
  var canNavigate by mutableStateOf(false)
    private set

  var canEdit by mutableStateOf(false)
    private set

  private var homeAction: (() -> Unit)? = null
  private var settingsAction: (() -> Unit)? = null
  private var editAction: (() -> Unit)? = null

  fun home() {
    homeAction?.invoke()
  }

  fun settings() {
    settingsAction?.invoke()
  }

  fun edit() {
    editAction?.invoke()
  }

  internal fun bind(home: (() -> Unit)?, settings: (() -> Unit)?, edit: (() -> Unit)?) {
    homeAction = home
    settingsAction = settings
    editAction = edit
    canNavigate = home != null
    canEdit = edit != null
  }

  internal fun clear() = bind(null, null, null)
}

@Composable
internal fun DesktopDashboardSidebar(
  list: DashboardListState,
  currentPath: String?,
  showingDashboard: Boolean,
  showingSettings: Boolean,
  enabled: Boolean,
  onDashboard: (String?) -> Unit,
  onSettings: () -> Unit,
) {
  val itemModifier =
    Modifier.alpha(if (enabled) 1f else 0.45f).semantics { if (!enabled) disabled() }
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(
      Modifier.width(224.dp).fillMaxHeight().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        "Terrazzo",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
      )
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        when (list) {
          DashboardListState.Loading -> Text("Loading dashboards…", Modifier.padding(16.dp))
          is DashboardListState.Error -> Text("Dashboards unavailable", Modifier.padding(16.dp))
          is DashboardListState.Ready ->
            list.dashboards.forEach { dashboard ->
              NavigationDrawerItem(
                label = { Text(dashboard.title) },
                icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                selected = showingDashboard && dashboard.urlPath == currentPath,
                onClick = { if (enabled) onDashboard(dashboard.urlPath) },
                modifier = itemModifier,
              )
            }
        }
      }
      HorizontalDivider(Modifier.padding(vertical = 8.dp))
      NavigationDrawerItem(
        label = { Text("Settings") },
        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
        selected = showingSettings,
        onClick = { if (enabled) onSettings() },
        modifier = itemModifier,
      )
      if (!enabled)
        Text(
          "Finish editing to switch dashboards",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(12.dp),
        )
    }
  }
}
