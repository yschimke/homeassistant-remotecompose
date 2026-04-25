package ee.schimke.terrazzo.wearsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.wearsync.proto.SyncStats
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import kotlinx.coroutines.launch

/**
 * Settings → Diagnostics. Mounted from the existing settings screen via
 * a small "Sync diagnostics" link, so the wear data-layer chatter is
 * visible to power users who want to make sure messages aren't being
 * fired too often.
 *
 * Surfaces:
 *   - the cumulative DataItem write / MessageClient send counts
 *   - the timestamp of the last lease arrival (which decides whether
 *     phone is currently streaming or batching)
 *   - rolling-window send frequency from [SyncStats.recentSendMs]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncDiagnosticsScreen(
    statsStore: MobileSyncStatsStore,
    streamActive: Boolean,
    onBack: () -> Unit,
) {
    val stats by statsStore.flow.collectAsState(initial = SyncStats())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "What the watch sees",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Phone publishes wear data via DataClient (cold reads) and " +
                    "MessageClient (live deltas). Watch holds a lease while its " +
                    "activity is foreground; when no lease is active phone falls " +
                    "back to batched DataStore writes to keep radio time low.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Stream", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = if (streamActive) "ACTIVE — pushing deltas" else "Idle — batching to DataStore",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (streamActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            StatRow("DataItem writes", stats.datastoreWrites.toString())
            StatRow("MessageClient sends", stats.messageSends.toString())
            StatRow(
                "Lease window",
                if (stats.lastLeaseAtMs == 0L) "never" else relativeNow(stats.lastLeaseAtMs)
                    + " (window: ${WearSyncPaths.LEASE_WINDOW_MS / 1000}s)",
            )
            StatRow(
                "Last DataItem write",
                if (stats.lastWriteAtMs == 0L) "never" else relativeNow(stats.lastWriteAtMs),
            )
            StatRow(
                "Last MessageClient send",
                if (stats.lastMessageAtMs == 0L) "never" else relativeNow(stats.lastMessageAtMs),
            )
            StatRow(
                "Send frequency (last ${stats.recentSendMs.size})",
                formatFrequency(stats.recentSendMs),
            )

            HorizontalDivider()

            OutlinedButton(onClick = { scope.launch { statsStore.reset() } }) {
                Text("Reset counters")
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun relativeNow(ms: Long): String {
    val deltaMs = (System.currentTimeMillis() - ms).coerceAtLeast(0L)
    return when {
        deltaMs < 1_000 -> "just now"
        deltaMs < 60_000 -> "${deltaMs / 1_000}s ago"
        deltaMs < 3_600_000 -> "${deltaMs / 60_000}m ago"
        else -> "${deltaMs / 3_600_000}h ago"
    }
}

/**
 * Format the rolling send window as either a per-minute rate (when we
 * have ≥ 2 timestamps) or a count. The window is `recentSendMs.size`
 * timestamps; their span in seconds is `(last - first) / 1000`.
 */
private fun formatFrequency(recent: List<Long>): String {
    if (recent.size < 2) return "n/a"
    val spanSec = ((recent.last() - recent.first()) / 1000.0).coerceAtLeast(0.001)
    val perSec = recent.size / spanSec
    val perMin = perSec * 60
    return "%.2f/min".format(perMin)
}
