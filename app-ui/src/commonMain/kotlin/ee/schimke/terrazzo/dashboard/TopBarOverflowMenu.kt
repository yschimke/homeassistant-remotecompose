package ee.schimke.terrazzo.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Trailing overflow menu in the dashboard top-bar. Carries the destinations the [DashboardSwitcher]
 * dropdown deliberately doesn't: Settings, installed widgets, sign-out. Dashboard quick-switching
 * stays in the title-side dropdown so users don't have to scroll past "Manage widgets" / "Settings"
 * entries to reach the dashboards they actually want to switch between.
 *
 * [onOpenAgent], when non-null, adds an "Edit with an agent" entry at the top. [onInstallOnWatch],
 * when non-null, adds "Install on watch" for a connected watch without the watch app.
 */
@Composable
fun TopBarOverflowMenu(
  onOpenSettings: () -> Unit,
  onOpenWidgets: () -> Unit,
  onOpenPinned: () -> Unit,
  onOpenWearWidgets: (() -> Unit)?,
  onOpenLogs: (() -> Unit)?,
  onSignOut: () -> Unit,
  onOpenAgent: (() -> Unit)? = null,
  onInstallOnWatch: (() -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  IconButton(onClick = { expanded = true }) {
    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
  }
  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
    // Set only on narrow windows, where the top bar folds its agent icon in here to give the
    // dashboard title the width.
    if (onOpenAgent != null) {
      DropdownMenuItem(
        text = { Text("Edit with an agent") },
        onClick = {
          expanded = false
          onOpenAgent()
        },
      )
    }
    DropdownMenuItem(
      text = { Text("Settings") },
      onClick = {
        expanded = false
        onOpenSettings()
      },
    )
    DropdownMenuItem(
      text = { Text("Manage widgets") },
      onClick = {
        expanded = false
        onOpenWidgets()
      },
    )
    DropdownMenuItem(
      text = { Text("Manage pinned") },
      onClick = {
        expanded = false
        onOpenPinned()
      },
    )
    // Hidden unless a paired Wear node has reported widget support.
    if (onOpenWearWidgets != null) {
      DropdownMenuItem(
        text = { Text("Wear widgets") },
        onClick = {
          expanded = false
          onOpenWearWidgets()
        },
      )
    }
    if (onInstallOnWatch != null) {
      DropdownMenuItem(
        text = { Text("Install on watch") },
        onClick = {
          expanded = false
          onInstallOnWatch()
        },
      )
    }
    // Hidden unless the user enabled the logs view in Settings.
    if (onOpenLogs != null) {
      DropdownMenuItem(
        text = { Text("Logs") },
        onClick = {
          expanded = false
          onOpenLogs()
        },
      )
    }
    DropdownMenuItem(
      text = { Text("Sign out") },
      onClick = {
        expanded = false
        onSignOut()
      },
    )
  }
}
