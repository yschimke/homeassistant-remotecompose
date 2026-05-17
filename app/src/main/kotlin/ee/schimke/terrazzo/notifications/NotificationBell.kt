package ee.schimke.terrazzo.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.HaNotification

/**
 * Top-bar bell that surfaces HA's *persistent notifications* — the same
 * list HA's frontend shows behind its bell icon. A badge with the count
 * appears when the list is non-empty; tapping opens a bottom sheet
 * listing every notification with title + message + timestamp.
 *
 * Pure presentational — the source of truth is
 * `HaSession.notifications`; this composable takes the materialised
 * list and renders. New-arrival snackbars are wired separately in
 * `DashboardsRoot` so this widget stays composable in isolation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationBell(
    notifications: List<HaNotification>,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { sheetOpen = true }, modifier = modifier) {
        BadgedBox(
            badge = {
                if (notifications.isNotEmpty()) {
                    Badge { Text(notifications.size.toString()) }
                }
            },
        ) {
            Icon(Icons.Outlined.Notifications, contentDescription = "Notifications")
        }
    }

    if (sheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
        ) {
            NotificationSheetContent(notifications)
        }
    }
}

@Composable
private fun NotificationSheetContent(notifications: List<HaNotification>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Notifications", style = MaterialTheme.typography.headlineSmall)
        if (notifications.isEmpty()) {
            Text(
                "Nothing from Home Assistant right now.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(notifications, key = { it.notificationId }) { n ->
                    NotificationRow(n)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: HaNotification) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val title = notification.title?.takeIf { it.isNotBlank() } ?: notification.notificationId
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (notification.message.isNotBlank()) {
            Text(notification.message, style = MaterialTheme.typography.bodyMedium)
        }
        notification.createdAt?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
    }
}
