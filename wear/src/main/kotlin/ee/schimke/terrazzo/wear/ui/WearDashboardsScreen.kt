package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.wearsync.proto.CardDoc
import ee.schimke.terrazzo.wearsync.proto.DashboardData

/**
 * Browse-mode list — one row per dashboard the phone published. Tap a
 * row to drill into [WearDashboardScreen].
 */
@Composable
fun WearDashboardsScreen(
    dashboards: List<DashboardData>,
    onDashboardPicked: (DashboardData) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        item { ListHeader { Text("Dashboards") } }

        if (dashboards.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Waiting for the phone to publish dashboards…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(dashboards) { dashboard ->
                Button(
                    onClick = { onDashboardPicked(dashboard) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Column {
                        Text(
                            text = dashboard.title.ifEmpty { dashboard.urlPath.ifEmpty { "Default" } },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${dashboard.cards.size} card${if (dashboard.cards.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("Back")
            }
        }
    }
}

/**
 * Single-dashboard drilldown — one [WearCardPlayer] per card, fed from
 * the [cardDocs] map keyed by `CardRef.id`.
 */
@Composable
fun WearDashboardScreen(
    dashboard: DashboardData,
    cardDocs: Map<String, CardDoc>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        item { ListHeader { Text(dashboard.title.ifEmpty { "Dashboard" }) } }

        if (dashboard.cards.isEmpty()) {
            item {
                Text(
                    text = "Empty dashboard",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        } else {
            items(dashboard.cards) { ref ->
                val doc = cardDocs[ref.id]
                if (doc != null) {
                    WearCardPlayer(doc)
                } else {
                    WearCardSkeleton(
                        title = ref.title.ifEmpty { ref.primaryEntityId.ifEmpty { ref.type } },
                    )
                }
            }
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("Back")
            }
        }
    }
}
