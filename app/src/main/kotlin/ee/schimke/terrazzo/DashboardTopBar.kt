package ee.schimke.terrazzo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.HaNotification
import ee.schimke.terrazzo.notifications.NotificationBell
import ee.schimke.terrazzo.ui.statusColors

/**
 * Below this window width the agent action moves from an icon into the overflow menu.
 *
 * The title is the most important thing in the bar, so the actions are budgeted against it. Every
 * action is a 48 dp touch target and the bar adds a 16 dp title inset and a 4 dp trailing pad, so
 * the title gets `width − 20 dp − 48 dp × actions`. With all five actions (agent, notifications,
 * connection, write mode, overflow) that is 150 dp at 410 dp; dropping the agent icon gives a 360
 * dp phone 148 dp — room for "3D Printing" in `titleLarge` (≈107 dp) plus its chevron.
 */
private val AgentActionMinWidth = 410.dp

/**
 * The dashboard view's top app bar: a [TopAppBar] plus, when the connection is not healthy, a slim
 * full-width status strip below it. The actions are icon-only so the dashboard title (the [title]
 * slot, usually a `DashboardSwitcher`) keeps the bar's width on a ~360 dp phone:
 * - the connection state is always a small coloured dot; the strip says the same thing in words (or
 *   shows progress) for every state but Connected, without taking width from the title;
 * - write mode is an [IconToggleButton] (pencil when writable, lock when read-only);
 * - the agent action folds into the overflow menu below [AgentActionMinWidth]. [overflowMenu]
 *   receives the agent callback to render as a menu item then, or `null` when the icon is shown.
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
  overflowMenu: @Composable (onOpenAgent: (() -> Unit)?) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier) {
    val agentInline = showAgentButton && maxWidth >= AgentActionMinWidth
    val agentInOverflow = showAgentButton && !agentInline
    Column(Modifier.fillMaxWidth()) {
      TopAppBar(
        title = title,
        actions = {
          if (agentInline) {
            IconButton(onClick = onOpenAgent) {
              Icon(Icons.Filled.AutoAwesome, contentDescription = "Edit with an agent")
            }
          }
          NotificationBell(
            notifications = notifications,
            onDismiss = onDismissNotification,
            onClearAll = onClearNotifications,
          )
          ConnectionStatusDot(status = connectionStatus, onClick = onRetryConnection)
          WriteModeToggle(writeMode = writeMode, onWriteModeChange = onWriteModeChange)
          overflowMenu(if (agentInOverflow) onOpenAgent else null)
        },
      )
      ConnectionStatusStrip(status = connectionStatus, onRetry = onRetryConnection)
    }
  }
}

/**
 * Connection state as a 10 dp dot in a 48 dp touch target, for every state. Its accessibility label
 * is the status; tapping it retries the connection.
 */
@Composable
internal fun ConnectionStatusDot(
  status: ConnectionStatus,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val statusColor = status.statusColor()
  IconButton(
    onClick = onClick,
    modifier = modifier.semantics { contentDescription = status.label },
  ) {
    Box(Modifier.size(10.dp).background(statusColor, CircleShape))
  }
}

/**
 * The text (or progress) half of the connection state, shown full-width under the app bar so it
 * never competes with the title: an indeterminate progress line while connecting, and a one-line
 * tonal banner that retries on tap while paused or failed. Nothing while connected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionStatusStrip(
  status: ConnectionStatus,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  when (status) {
    ConnectionStatus.Connected -> Unit
    ConnectionStatus.Connecting ->
      LinearProgressIndicator(
        color = status.statusColor(),
        modifier =
          modifier.fillMaxWidth().semantics { contentDescription = "Connecting to Home Assistant" },
      )
    ConnectionStatus.Disconnected,
    ConnectionStatus.Failed -> {
      val failed = status == ConnectionStatus.Failed
      val colors = MaterialTheme.colorScheme
      Surface(
        onClick = onRetry,
        color = if (failed) colors.errorContainer else colors.surfaceContainerHigh,
        contentColor = if (failed) colors.onErrorContainer else colors.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
      ) {
        // At least the touch target's height: a clickable Surface keeps 48 dp of touch target
        // around a shorter banner as transparent space, a band above and below it.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.heightIn(min = 48.dp)
              .windowInsetsPadding(
                TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal)
              )
              .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          Text(
            text = if (failed) "Can't reach Home Assistant" else "Paused — tap to reconnect",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (failed) {
            Text(
              text = "Retry",
              style = MaterialTheme.typography.labelLarge,
              color = colors.error,
              modifier = Modifier.padding(start = 16.dp),
            )
          }
        }
      }
    }
  }
}

/**
 * Session write-mode toggle: a 48 dp icon toggle showing a pencil when writes are enabled and a
 * lock when the dashboard is read-only. Screen readers get "Write mode" plus a Write / Read only
 * state.
 */
@Composable
internal fun WriteModeToggle(
  writeMode: Boolean,
  onWriteModeChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  IconToggleButton(
    checked = writeMode,
    onCheckedChange = onWriteModeChange,
    modifier =
      modifier.semantics {
        contentDescription = "Write mode"
        stateDescription = if (writeMode) "Write" else "Read only"
      },
  ) {
    Icon(
      imageVector = if (writeMode) Icons.Filled.Edit else Icons.Filled.Lock,
      contentDescription = null,
    )
  }
}

/** Theme-aware chrome colour for the connection dot and progress line. */
@Composable
@ReadOnlyComposable
private fun ConnectionStatus.statusColor(): Color =
  when (this) {
    ConnectionStatus.Connected -> statusColors.success
    ConnectionStatus.Connecting -> statusColors.warning
    ConnectionStatus.Disconnected -> statusColors.neutral
    ConnectionStatus.Failed -> statusColors.error
  }
