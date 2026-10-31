package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedSection

/**
 * Drill-in destination for a pinned section. Renders the synced card configs as Remote Compose
 * documents with current values and the selected card style. A long press (or a tap the card
 * doesn't take) opens a card full screen ([onOpenCard]); a double pinch scrolls to the next card.
 *
 * If the section was unpinned mid-flight (e.g. the user toggled it off on the phone), [section] is
 * null and we fall back to the empty state — the caller is expected to navigate back; rendering
 * still tolerates the missing data so we don't NPE during the brief gap.
 */
@Composable
fun WearSectionScreen(
  section: PinnedSection?,
  values: WearValues,
  modifier: Modifier = Modifier,
  onInputSend: (suspend (String, Map<String, String>) -> Unit)? = null,
  onOpenCard: (CardSummary) -> Unit = {},
  onNavigate: (String) -> Unit = {},
) {
  WearListScreen(modifier = modifier, gestureScroll = true) {
    val title = section?.title?.ifEmpty { null } ?: "Section"
    item { ListHeader { Text(title) } }

    val cards = withoutRepeatedHeading(section?.cards.orEmpty(), title)
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
        if (card.type == ee.schimke.ha.model.MESSAGE_CARD_TYPE) {
          WearMessageCard(card.rawJson, values, onInputSend)
        } else {
          WearRemoteCard(card, values, onOpen = { onOpenCard(card) }, onNavigate = onNavigate)
        }
      }
    }
  }
}

/**
 * [cards] without a leading heading card that only repeats the screen's [title]: a Home Assistant
 * section usually opens with its own heading, which the list header already shows.
 */
internal fun withoutRepeatedHeading(cards: List<CardSummary>, title: String): List<CardSummary> {
  val first = cards.firstOrNull() ?: return cards
  return if (first.type == "heading" && first.title.trim() == title.trim()) cards.drop(1) else cards
}
