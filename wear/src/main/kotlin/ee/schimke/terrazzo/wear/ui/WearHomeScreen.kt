package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Default Wear screen — surfaces the pinned cards the user already
 * curated phone-side. Each pinned `CardRef` is paired with the
 * `CardDoc` (phone-baked `.rc` bytes) at `/wear/card/<id>`; if the doc
 * isn't here yet we render a small skeleton with the ref's title.
 *
 * Demo mode is purely a phone-side concept; the watch only renders
 * what phone publishes plus a banner when `WearSettings.demoMode` is
 * set.
 */
@Composable
fun WearHomeScreen(
    settings: WearSettings,
    pinned: PinnedCardSet,
    cardDocs: Map<String, CardDoc>,
    onBrowseDashboards: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        item {
            ListHeader { Text(if (settings.demoMode) "Pinned · Demo" else "Pinned") }
        }
        if (settings.demoMode) {
            item { DemoBanner() }
        }

        if (pinned.cards.isEmpty()) {
            item { EmptyPinned() }
        } else {
            items(pinned.cards) { card ->
                val doc = cardDocs[card.id]
                if (doc != null) {
                    WearCardPlayer(doc)
                } else {
                    WearCardSkeleton(
                        title = card.title.ifEmpty { card.primaryEntityId.ifEmpty { card.type } },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item {
            Button(
                onClick = onBrowseDashboards,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("Browse dashboards")
            }
        }
        item {
            Button(
                onClick = onOpenAbout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("About")
            }
        }
    }
}

@Composable
private fun DemoBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Phone is in demo mode",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun EmptyPinned() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No pinned cards.\nLong-press a card on the phone to add one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
