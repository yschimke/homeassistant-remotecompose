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
 * Trailing overflow menu in the dashboard top-bar. Carries the
 * destinations the [DashboardSwitcher] dropdown deliberately doesn't:
 * Settings, installed widgets, sign-out. Dashboard quick-switching
 * stays in the title-side dropdown so users don't have to scroll past
 * "Manage widgets" / "Settings" entries to reach the dashboards
 * they actually want to switch between.
 */
@Composable
fun TopBarOverflowMenu(
    onOpenSettings: () -> Unit,
    onOpenWidgets: () -> Unit,
    onSignOut: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        DropdownMenuItem(
            text = { Text("Settings") },
            onClick = { expanded = false; onOpenSettings() },
        )
        DropdownMenuItem(
            text = { Text("Manage widgets") },
            onClick = { expanded = false; onOpenWidgets() },
        )
        DropdownMenuItem(
            text = { Text("Sign out") },
            onClick = { expanded = false; onSignOut() },
        )
    }
}
