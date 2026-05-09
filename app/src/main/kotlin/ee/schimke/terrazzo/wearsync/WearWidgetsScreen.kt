package ee.schimke.terrazzo.wearsync

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.SlotSize
import ee.schimke.terrazzo.core.pin.WearWidgetSlot
import kotlinx.coroutines.launch

/**
 * Mobile screen for configuring the 5 wear-widget slots. Each slot
 * holds a reference to a pinned card (by [MobilePinnedCard.key]); the
 * watch enables one widget provider per assigned slot, and the system
 * widget picker on the watch only surfaces the enabled ones.
 *
 * This screen is reachable from the dashboard top-bar overflow menu —
 * but only when [WearCapabilityProbe] reports that a paired Wear
 * node advertises widget support. The menu entry is hidden otherwise,
 * so this screen always has a meaningful effect when the user gets to
 * it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WearWidgetsScreen(onBack: () -> Unit) {
    val pinStore = LocalTerrazzoGraph.current.pinStore
    val slotsStore = LocalTerrazzoGraph.current.wearWidgetSlotsStore
    val scope = rememberCoroutineScope()

    val slots by slotsStore.slots.collectAsState(initial = emptyList())
    val pinnedCards by pinStore.cards.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wear widgets") },
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
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Each slot maps to one widget provider on your watch. " +
                    "Empty slots are hidden from the watch's widget picker.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            if (pinnedCards.isEmpty()) {
                Text(
                    text = "Pin some cards first — slots can only point at pinned cards.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(slots, key = { it.slotIndex }) { slot ->
                    SlotRow(
                        slot = slot,
                        pinnedCards = pinnedCards,
                        onAssign = { card ->
                            scope.launch { slotsStore.setSlot(slot.slotIndex, card.key) }
                        },
                        onClear = {
                            scope.launch { slotsStore.clearSlot(slot.slotIndex) }
                        },
                        onSizeChange = { size ->
                            scope.launch { slotsStore.setSize(slot.slotIndex, size) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    slot: WearWidgetSlot,
    pinnedCards: List<MobilePinnedCard>,
    onAssign: (MobilePinnedCard) -> Unit,
    onClear: () -> Unit,
    onSizeChange: (SlotSize) -> Unit,
) {
    val assignedCard = pinnedCards.firstOrNull { it.key == slot.cardKey }
    val pickerOpen = remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = pinnedCards.isNotEmpty()) { pickerOpen.value = true }
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Slot ${slot.slotIndex + 1}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = when {
                            assignedCard != null ->
                                assignedCard.card.title.ifEmpty { assignedCard.card.type }
                            slot.isAssigned ->
                                "Assigned card no longer pinned"
                            else -> "Unassigned"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (assignedCard != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (slot.isAssigned) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear slot")
                    }
                }
            }

            // Size picker — Small / Large / Both. Only meaningful for
            // assigned slots; greyed out otherwise so the layout stays
            // stable but a tap doesn't quietly mutate state for an
            // empty slot.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SlotSize.entries.forEach { option ->
                    FilterChip(
                        selected = slot.size == option,
                        enabled = slot.isAssigned,
                        onClick = { onSizeChange(option) },
                        label = { Text(option.displayName) },
                    )
                }
            }
        }

        DropdownMenu(
            expanded = pickerOpen.value,
            onDismissRequest = { pickerOpen.value = false },
        ) {
            pinnedCards.forEach { card ->
                DropdownMenuItem(
                    text = { Text(card.card.title.ifEmpty { card.card.type }) },
                    onClick = {
                        pickerOpen.value = false
                        onAssign(card)
                    },
                )
            }
        }
    }
}

private val SlotSize.displayName: String
    get() = when (this) {
        SlotSize.SmallOnly -> "Small"
        SlotSize.LargeOnly -> "Large"
        SlotSize.Both -> "Both"
    }

