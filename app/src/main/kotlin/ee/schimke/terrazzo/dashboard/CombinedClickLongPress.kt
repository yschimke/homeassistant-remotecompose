package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Thin wrapper around `Modifier.combinedClickable` tuned for the
 * dashboard: tap for a future "open card detail" navigation, long
 * press for "install as widget". Both are optional.
 */
fun Modifier.combinedClickLongPress(
    onClick: () -> Unit = {},
    onLongPress: () -> Unit,
): Modifier = this.then(
    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
)
