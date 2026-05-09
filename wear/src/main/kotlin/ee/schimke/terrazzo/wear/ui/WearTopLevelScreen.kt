package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Wear's top-level screen. Replaces the legacy "home" surface — there
 * is no longer a separate landing page; the pinned set IS the landing
 * page.
 *
 * Renders the user's pinned items (cards + sections) as a single list
 * ordered by [PinnedCard.orderIndex] / [PinnedSection.orderIndex],
 * which the mobile pin store keeps in lockstep:
 *
 *   - **Pinned sections** show as a tonal button with the section
 *     title + "N cards"; tap drills into [WearSectionScreen].
 *   - **Pinned cards** show their title + live entity value inline
 *     (no drill-in — the user pinned them precisely because they want
 *     top-level access).
 *
 * Cards inside a section can also be pinned independently as
 * top-level entries; in that case they show twice (once at the top
 * level, once inside the section). That's the v1 behaviour
 * deliberately — the user pinned both, we render both.
 *
 * "Browse dashboards" and "Settings" stay at the bottom as utility
 * destinations.
 */
@Composable
fun WearTopLevelScreen(
    settings: WearSettings,
    pinned: PinnedCardSet,
    sections: PinnedSectionSet,
    values: Map<String, EntityValue>,
    onOpenSection: (PinnedSection) -> Unit,
    onBrowseDashboards: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    val items = remember(pinned, sections) { mergeOrdered(pinned.cards, sections.sections) }

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

        if (items.isEmpty()) {
            item { EmptyPinned() }
        } else {
            items(items, key = { it.key }) { entry ->
                when (entry) {
                    is TopLevelEntry.Card ->
                        PinnedCardRow(card = entry.card, value = values[entry.card.card.primaryEntityId])
                    is TopLevelEntry.Section ->
                        PinnedSectionRow(
                            section = entry.section,
                            onClick = { onOpenSection(entry.section) },
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
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Text("Settings")
            }
        }
    }
}

/**
 * Interleave pinned cards and pinned sections by their shared
 * `orderIndex`. Tie-break by stable key so renders don't flicker when
 * two pins share an index.
 */
internal fun mergeOrdered(
    cards: List<PinnedCard>,
    sections: List<PinnedSection>,
): List<TopLevelEntry> {
    val combined = ArrayList<TopLevelEntry>(cards.size + sections.size)
    cards.forEach { combined += TopLevelEntry.Card(it) }
    sections.forEach { combined += TopLevelEntry.Section(it) }
    return combined.sortedWith(
        compareBy<TopLevelEntry> { it.orderIndex }.thenBy { it.key },
    )
}

internal sealed interface TopLevelEntry {
    val key: String
    val orderIndex: Int

    data class Card(val card: PinnedCard) : TopLevelEntry {
        override val key: String
            get() = "card:${card.cardKey.ifEmpty { card.card.primaryEntityId }}"
        override val orderIndex: Int get() = card.orderIndex
    }

    data class Section(val section: PinnedSection) : TopLevelEntry {
        override val key: String
            get() = "section:${section.sectionKey.ifEmpty { section.title }}"
        override val orderIndex: Int get() = section.orderIndex
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
            text = "Nothing pinned yet.\nPin sections or cards from the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PinnedCardRow(card: PinnedCard, value: EntityValue?) {
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
            Column {
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

@Composable
private fun PinnedSectionRow(section: PinnedSection, onClick: () -> Unit) {
    val title = section.title.ifEmpty { "Section" }
    val cardCount = section.cards.size
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$cardCount card${if (cardCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
