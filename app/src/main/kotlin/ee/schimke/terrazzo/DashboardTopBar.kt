package ee.schimke.terrazzo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.HaNotification
import ee.schimke.terrazzo.notifications.NotificationBell
import ee.schimke.terrazzo.ui.statusColors

/**
 * The dashboard view's top app bar. The actions are kept compact so the dashboard title (the
 * [title] slot, usually a `DashboardSwitcher`) still fits on a ~360 dp phone:
 * - the connection state is a small coloured dot while [ConnectionStatus.Connected], and only grows
 *   into a labelled chip for the states worth surfacing (connecting, paused, failed);
 * - write mode is a bare [Switch] whose thumb icon (pencil / lock) and semantics carry the state,
 *   rather than a separate "Read" / "Write" label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardTopBar(
  title: @Composable () -> Unit,
  showAgentButton: Boolean,
  onOpenAgent: () -> Unit,
  notifications: List<HaNotification>,
  onDismissNotification: (HaNotification) -> Unit,
  onClearNotifications: () -> Unit,
  connectionStatus: ConnectionStatus,
  onRetryConnection: () -> Unit,
  writeMode: Boolean,
  onWriteModeChange: (Boolean) -> Unit,
  overflowMenu: @Composable () -> Unit,
) {
  TopAppBar(
    title = title,
    actions = {
      if (showAgentButton) {
        IconButton(onClick = onOpenAgent) {
          Icon(Icons.Filled.AutoAwesome, contentDescription = "Edit with an agent")
        }
      }
      NotificationBell(
        notifications = notifications,
        onDismiss = onDismissNotification,
        onClearAll = onClearNotifications,
      )
      ConnectionStatusIndicator(status = connectionStatus, onClick = onRetryConnection)
      WriteModeSwitch(
        writeMode = writeMode,
        onWriteModeChange = onWriteModeChange,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
      overflowMenu()
    },
  )
}

/**
 * Connection state for the top bar. Connected — the steady state — is a small dot whose
 * accessibility label is the status; the other states keep the full text chip so they are
 * noticeable. Tapping either retries the connection.
 */
@Composable
internal fun ConnectionStatusIndicator(
  status: ConnectionStatus,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val statusColor = status.statusColor()
  if (status == ConnectionStatus.Connected) {
    // A full-height but narrow tap target: an IconButton would reserve 48 dp of width for a 10 dp
    // dot, which is exactly the width the title needs on a phone.
    Box(
      contentAlignment = Alignment.Center,
      modifier =
        modifier
          .size(width = 24.dp, height = 48.dp)
          .clickable(onClickLabel = "Retry connection", role = Role.Button, onClick = onClick)
          .semantics { contentDescription = status.label },
    ) {
      Box(Modifier.size(10.dp).background(statusColor, CircleShape))
    }
  } else {
    Surface(
      onClick = onClick,
      color = statusColor.copy(alpha = 0.16f),
      shape = MaterialTheme.shapes.small,
      modifier = modifier.padding(horizontal = 4.dp),
    ) {
      Text(
        text = status.label,
        color = statusColor,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
      )
    }
  }
}

/**
 * Session write-mode toggle. The thumb shows a pencil when writes are enabled and a lock when the
 * dashboard is read-only; screen readers get "Write mode" plus a Write / Read only state.
 */
@Composable
internal fun WriteModeSwitch(
  writeMode: Boolean,
  onWriteModeChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Switch(
    checked = writeMode,
    onCheckedChange = onWriteModeChange,
    thumbContent = {
      Icon(
        imageVector = if (writeMode) Icons.Filled.Edit else Icons.Filled.Lock,
        contentDescription = null,
        modifier = Modifier.size(SwitchDefaults.IconSize),
      )
    },
    modifier =
      modifier.semantics {
        contentDescription = "Write mode"
        stateDescription = if (writeMode) "Write" else "Read only"
      },
  )
}

/** Theme-aware chrome colour for the top-bar connection indicator. */
@Composable
@ReadOnlyComposable
private fun ConnectionStatus.statusColor(): Color =
  when (this) {
    ConnectionStatus.Failed -> statusColors.error
    ConnectionStatus.Connecting -> statusColors.success
    ConnectionStatus.Connected -> statusColors.info
    ConnectionStatus.Disconnected -> statusColors.neutral
  }
