package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import ee.schimke.terrazzo.wearsync.proto.PinnedSection

/**
 * Drill-in destination for a pinned section. Shows the section's cards
 * as captured-at-pin-time on the phone, overlaid with live values from
 * the wear sync repository's [EntityValue] map.
 *
 * If the section was unpinned mid-flight (e.g. the user toggled it off
 * on the phone), [section] is null and we fall back to the empty state
 * — the caller is expected to navigate back; rendering still tolerates
 * the missing data so we don't NPE during the brief gap.
 */
@Composable
fun WearSectionScreen(
    section: PinnedSection?,
    values: Map<String, EntityValue>,
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
        item {
            ListHeader { Text(section?.title?.ifEmpty { "Section" } ?: "Section") }
        }

        val cards = section?.cards.orEmpty()
        if (cards.isEmpty()) {
            item {
                Text(
                    text = "No cards in this section.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        } else {
            items(cards) { card ->
                val value = values[card.primaryEntityId]
                val displayState = value?.let { v ->
                    buildString {
                        append(v.state)
                        if (v.unit.isNotEmpty()) append(" ${v.unit}")
                    }
                }.orEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = card.title.ifEmpty { card.primaryEntityId.ifEmpty { card.type } },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (displayState.isNotEmpty()) {
                        Text(
                            text = displayState,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
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
