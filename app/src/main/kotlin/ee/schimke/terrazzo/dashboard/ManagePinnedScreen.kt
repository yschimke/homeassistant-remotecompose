package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.MobilePinnedSection
import kotlinx.coroutines.launch

/**
 * Manages the user's Wear pin set: lists every pinned card and pinned
 * section in their unified ordering, lets the user reorder via up/down
 * arrows (drag handles are a polish follow-up), and lets them unpin.
 *
 * Cards and sections share a single ordered list — the position here
 * is the position the watch's top-level nav will render. New pins land
 * at the tail; the user reshuffles from this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagePinnedScreen(onBack: () -> Unit) {
    val pinStore = LocalTerrazzoGraph.current.pinStore
    val scope = rememberCoroutineScope()
    val cards by pinStore.cards.collectAsState(initial = emptyList())
    val sections by pinStore.sections.collectAsState(initial = emptyList())

    val items: List<PinRowItem> = remember(cards, sections) {
        buildList {
            cards.forEach { add(PinRowItem.Card(it)) }
            sections.forEach { add(PinRowItem.Section(it)) }
        }.sortedBy { it.orderIndex }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage pinned") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No pins yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pin sections from a dashboard's section heading or pin cards from " +
                        "the long-press sheet. Pinned items become Wear nav destinations.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.key }) { item ->
                val index = items.indexOfFirst { it.key == item.key }
                PinRow(
                    item = item,
                    canMoveUp = index > 0,
                    canMoveDown = index >= 0 && index < items.lastIndex,
                    onMoveUp = {
                        scope.launch {
                            val reordered = items.toMutableList()
                                .apply { add(index - 1, removeAt(index)) }
                                .map { it.key }
                            pinStore.reorder(reordered)
                        }
                    },
                    onMoveDown = {
                        scope.launch {
                            val reordered = items.toMutableList()
                                .apply { add(index + 1, removeAt(index)) }
                                .map { it.key }
                            pinStore.reorder(reordered)
                        }
                    },
                    onUnpin = {
                        scope.launch {
                            when (item) {
                                is PinRowItem.Card -> pinStore.unpinCard(item.key)
                                is PinRowItem.Section -> pinStore.unpinSection(item.key)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PinRow(
    item: PinRowItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUnpin: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
            IconButton(onClick = onUnpin) {
                Icon(Icons.Filled.Close, contentDescription = "Unpin")
            }
        }
    }
}

private sealed interface PinRowItem {
    val key: String
    val orderIndex: Int
    val title: String
    val subtitle: String

    data class Card(val card: MobilePinnedCard) : PinRowItem {
        override val key: String get() = card.key
        override val orderIndex: Int get() = card.orderIndex
        override val title: String get() = card.card.title.ifEmpty { card.card.type }
        override val subtitle: String
            get() = "Card · ${card.dashboardUrlPath.ifEmpty { "default dashboard" }}"
    }

    data class Section(val section: MobilePinnedSection) : PinRowItem {
        override val key: String get() = section.key
        override val orderIndex: Int get() = section.orderIndex
        override val title: String get() = section.title.ifEmpty { "Untitled section" }
        override val subtitle: String
            get() = "Section · ${section.dashboardUrlPath.ifEmpty { "default dashboard" }} (${section.cards.size} cards)"
    }
}
