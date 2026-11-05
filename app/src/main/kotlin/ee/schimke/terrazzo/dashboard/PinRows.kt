package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * The user's Wear pin set as rows for the "On your watch" section of the widgets screen
 * ([ee.schimke.terrazzo.widget.WidgetsScreen]): every pinned card and pinned section in their
 * unified ordering, which is the order the watch's top-level nav renders. New pins land at the
 * tail; the user reshuffles with [PinRow]'s arrows.
 */
@Composable
internal fun rememberPinRows(): PinRows {
  val pinStore = LocalTerrazzoGraph.current.pinStore
  val scope = rememberCoroutineScope()
  val cards by pinStore.cards.collectAsState(initial = emptyList())
  val sections by pinStore.sections.collectAsState(initial = emptyList())
  val items: List<PinRowItem> =
    remember(cards, sections) {
      buildList {
        cards.forEach { add(PinRowItem.Card(it)) }
        sections.forEach { add(PinRowItem.Section(it)) }
      }
        .sortedBy { it.orderIndex }
    }
  return PinRows(
    items = items,
    onReorder = { newOrder -> scope.launch { pinStore.reorder(newOrder) } },
    onUnpin = { item ->
      scope.launch {
        when (item) {
          is PinRowItem.Card -> pinStore.unpinCard(item.key)
          is PinRowItem.Section -> pinStore.unpinSection(item.key)
        }
      }
    },
  )
}

/** The pinned rows and what reordering or unpinning one does. */
internal class PinRows(
  val items: List<PinRowItem>,
  val onReorder: (List<String>) -> Unit,
  val onUnpin: (PinRowItem) -> Unit,
)

/** [items] with the row at [index] moved by [delta] (-1 up, +1 down), as keys. */
internal fun reorderedKeys(items: List<PinRowItem>, index: Int, delta: Int): List<String> =
  items.toMutableList().apply { add(index + delta, removeAt(index)) }.map { it.key }

@Composable
internal fun PinRow(
  item: PinRowItem,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onUnpin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier =
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
        Icon(Icons.Filled.Close, contentDescription = "Remove from watch")
      }
    }
  }
}

internal sealed interface PinRowItem {
  val key: String
  val orderIndex: Int
  val title: String
  val subtitle: String

  data class Card(val card: MobilePinnedCard) : PinRowItem {
    override val key: String
      get() = card.key

    override val orderIndex: Int
      get() = card.orderIndex

    override val title: String
      get() = card.card.title.ifEmpty { card.card.type }

    override val subtitle: String
      get() = "Card · ${card.dashboardUrlPath.ifEmpty { "default dashboard" }}"
  }

  data class Section(val section: MobilePinnedSection) : PinRowItem {
    override val key: String
      get() = section.key

    override val orderIndex: Int
      get() = section.orderIndex

    override val title: String
      get() = section.title.ifEmpty { "Untitled section" }

    override val subtitle: String
      get() =
        "Section · ${section.dashboardUrlPath.ifEmpty { "default dashboard" }} (${section.cards.size} cards)"
  }
}
