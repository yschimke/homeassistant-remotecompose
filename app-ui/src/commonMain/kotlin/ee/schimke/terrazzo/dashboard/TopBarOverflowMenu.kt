package ee.schimke.terrazzo.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
 * stays in the title-side dropdown so users don't have to scroll past "Home screen widgets" /
 * "Settings" entries to reach the dashboards they actually want to switch between.
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
    val close = { expanded = false }
    // Set only on narrow windows, where the top bar folds its agent icon in here to give the
    // dashboard title the width.
    onOpenAgent?.let { MenuEntry("Edit with an agent", close, it) }
    // Where cards live outside the app: one entry per place, named for the place rather than the
    // mechanism, so "pinned" and "widgets" no longer read as the same thing twice.
    MenuEntry("Home screen widgets", close, onOpenWidgets)
    MenuEntry("Cards on your watch", close, onOpenPinned)
    // Hidden unless a paired Wear node has reported widget support.
    onOpenWearWidgets?.let { MenuEntry("Watch widgets", close, it) }
    onInstallOnWatch?.let { MenuEntry("Install on watch", close, it) }
    HorizontalDivider()
    MenuEntry("Settings", close, onOpenSettings)
    // Hidden unless the user enabled the logs view in Settings.
    onOpenLogs?.let { MenuEntry("Logs", close, it) }
    MenuEntry("Sign out", close, onSignOut)
  }
}

@Composable
private fun MenuEntry(label: String, close: () -> Unit, onClick: () -> Unit) {
  DropdownMenuItem(
    text = { Text(label) },
    onClick = {
      close()
      onClick()
    },
  )
}
