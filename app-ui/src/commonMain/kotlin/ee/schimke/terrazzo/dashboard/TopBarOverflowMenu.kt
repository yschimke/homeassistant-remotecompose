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
 * stays in the title-side dropdown so users don't have to scroll past "Widgets" / "Settings"
 * entries to reach the dashboards they actually want to switch between.
 *
 * [onEditDashboard], when non-null, adds "Edit dashboard" at the top, and [onManageDashboards]
 * "Manage dashboards" (which of Home Assistant's dashboards the switcher lists) after it.
 * [onInstallOnWatch], when non-null, adds "Install on watch" for a connected watch without the
 * watch app. [onOpenRoom], when non-null, leads with "This room": controls for the room the user is
 * in.
 */
@Composable
fun TopBarOverflowMenu(
  onOpenSettings: () -> Unit,
  onOpenWidgets: () -> Unit,
  onOpenPinned: () -> Unit,
  onOpenLogs: (() -> Unit)?,
  onSignOut: () -> Unit,
  onInstallOnWatch: (() -> Unit)? = null,
  onEditDashboard: (() -> Unit)? = null,
  onManageDashboards: (() -> Unit)? = null,
  onOpenRoom: (() -> Unit)? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  IconButton(onClick = { expanded = true }) {
    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
  }
  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
    val close = { expanded = false }
    onOpenRoom?.let {
      MenuEntry("This room", close, it)
      HorizontalDivider()
    }
    onEditDashboard?.let { MenuEntry("Edit dashboard", close, it) }
    onManageDashboards?.let { MenuEntry("Manage dashboards", close, it) }
    // Where cards live outside the app: one entry per place, named for the place rather than the
    // mechanism, so "pinned" and "widgets" no longer read as the same thing twice.
    // "Widgets": the phone's home screen and the watch's alike, the name the watch points to.
    MenuEntry("Widgets", close, onOpenWidgets)
    MenuEntry("Cards on your watch", close, onOpenPinned)
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
