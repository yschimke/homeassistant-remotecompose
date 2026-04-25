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
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Default Wear screen — the watch surfaces the cards the user has
 * already curated as widgets on the phone. Users tap "Dashboards" to
 * browse the full HA library, or "Settings" for the (now small) theme
 * picker.
 *
 * Demo mode is purely a phone-side concept; we just render what the
 * phone publishes and stick a small "Demo" banner up top when
 * `WearSettings.demoMode` is set.
 */
@Composable
fun WearHomeScreen(
    settings: WearSettings,
    pinned: PinnedCardSet,
    values: Map<String, EntityValue>,
    onBrowseDashboards: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                PinnedRow(card = card, value = values[card.card.primaryEntityId])
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
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("Settings")
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

@Composable
private fun PinnedRow(card: PinnedCard, value: EntityValue?) {
    val title = card.card.title.ifEmpty { card.card.primaryEntityId.ifEmpty { card.card.type } }
    val displayState = value?.let { v ->
        buildString {
            append(v.state)
            if (v.unit.isNotEmpty()) append(" ${v.unit}")
        }
    }.orEmpty()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.layout.Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (displayState.isNotEmpty()) {
                    Text(
                        text = displayState,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (card.card.primaryEntityId.isNotEmpty()) {
                    Text(
                        text = card.card.primaryEntityId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
