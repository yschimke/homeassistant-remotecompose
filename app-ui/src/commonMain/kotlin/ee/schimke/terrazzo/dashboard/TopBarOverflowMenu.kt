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
 * dropdown deliberately doesn't: widgets, Settings, sign-out. Dashboard quick-switching stays in
 * the title-side dropdown so users don't have to scroll past other entries to reach the dashboards
 * they actually want to switch between.
 *
 * Kept short on purpose: managing which dashboards are listed and the debug logs live in Settings.
 * [onEditDashboard], when non-null, adds "Edit dashboard", which is also where the agent is asked:
 * editing by hand and by agent are one flow. [onInstallOnWatch], when non-null, adds "Install on
 * watch" for a connected watch without the watch app. [onOpenRoom], when non-null, leads with "This
 * room": controls for the room the user is in.
 */
@Composable
fun TopBarOverflowMenu(
  onOpenSettings: () -> Unit,
  /** "Manage widgets": home screen and watch widgets, and the cards the watch shows. */
  onOpenWidgets: () -> Unit,
  onSignOut: () -> Unit,
  onInstallOnWatch: (() -> Unit)? = null,
  onEditDashboard: (() -> Unit)? = null,
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
    // One place for cards outside the app: the phone's home screen, the watch's widgets and the
    // cards the watch app shows.
    MenuEntry("Manage widgets", close, onOpenWidgets)
    onInstallOnWatch?.let { MenuEntry("Install on watch", close, it) }
    HorizontalDivider()
    MenuEntry("Settings", close, onOpenSettings)
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
