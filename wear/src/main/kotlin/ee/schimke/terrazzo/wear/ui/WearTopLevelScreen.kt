package ee.schimke.terrazzo.wear.ui

import androidx.activity.compose.ReportDrawn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings

/**
 * Wear's top-level screen. Replaces the legacy "home" surface — there is no longer a separate
 * landing page; the pinned set IS the landing page.
 *
 * Renders the user's pinned items (cards + sections) as a single list ordered by
 * [PinnedCard.orderIndex] / [PinnedSection.orderIndex], which the mobile pin store keeps in
 * lockstep:
 *
 * - **Pinned sections** show as a tonal button with the section title + "N cards"; tap drills into
 *   [WearSectionScreen].
 * - **Pinned cards** play their Remote Compose content inline: the user pinned them precisely
 *   because they want top-level access. A long press (or a tap the card doesn't take) opens one
 *   full screen ([onOpenCard], [WearCardScreen]).
 *
 * A double pinch scrolls to the next entry ([WearListScreen]'s gesture scroll).
 *
 * Cards inside a section can also be pinned independently as top-level entries; in that case they
 * show twice (once at the top level, once inside the section). That's the v1 behaviour deliberately
 * — the user pinned both, we render both.
 *
 * The watch only shows what the phone chose: everything is pinned, ordered and styled there. With
 * nothing pinned the list says so and offers to open the phone app ([onOpenOnPhone]).
 *
 * "Browse dashboards", "Widgets" (on watches that show them, [onOpenWidgets]) and "Settings" stay
 * at the bottom as utility destinations.
 */
@Composable
fun WearTopLevelScreen(
  settings: WearSettings,
  pinned: PinnedCardSet,
  sections: PinnedSectionSet,
  values: WearValues,
  onOpenSection: (PinnedSection) -> Unit,
  onBrowseDashboards: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
  onInputSend: (suspend (String, Map<String, String>) -> Unit)? = null,
  onOpenCard: (PinnedCard) -> Unit = {},
  onOpenOnPhone: (() -> Unit)? = null,
  onOpenWidgets: (() -> Unit)? = null,
) {
  val items = remember(pinned, sections) { mergeOrdered(pinned.cards, sections.sections) }
  // The pinned list composes here: the launch is fully drawn (`timeToFullDisplayMs`).
  ReportDrawn()

  WearListScreen(modifier = modifier, spacing = 6.dp, gestureScroll = true) {
    item { ListHeader { Text("Pinned") } }
    if (settings.demoMode) {
      item { DemoBanner() }
    }

    if (items.isEmpty()) {
      item { EmptyPinned() }
      onOpenOnPhone?.let { open -> item { OpenOnPhoneButton(open) } }
    } else {
      items(items, key = { it.key }) { entry ->
        when (entry) {
          is TopLevelEntry.Card -> {
            val card = entry.card.card
            if (card.type == ee.schimke.ha.model.MESSAGE_CARD_TYPE) {
              WearMessageCard(card.rawJson, values, onInputSend)
            } else {
              WearRemoteCard(card, values, onOpen = { onOpenCard(entry.card) })
            }
          }
          is TopLevelEntry.Section ->
            PinnedSectionRow(section = entry.section, onClick = { onOpenSection(entry.section) })
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
    onOpenWidgets?.let { open ->
      item {
        Button(
          onClick = open,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
          Text("Widgets")
        }
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
 * Interleave pinned cards and pinned sections by their shared `orderIndex`. Tie-break by stable key
 * so renders don't flicker when two pins share an index.
 */
internal fun mergeOrdered(
  cards: List<PinnedCard>,
  sections: List<PinnedSection>,
): List<TopLevelEntry> {
  val combined = ArrayList<TopLevelEntry>(cards.size + sections.size)
  cards.forEach { combined += TopLevelEntry.Card(it) }
  sections.forEach { combined += TopLevelEntry.Section(it) }
  return combined.sortedWith(compareBy<TopLevelEntry> { it.orderIndex }.thenBy { it.key })
}

internal sealed interface TopLevelEntry {
  val key: String
  val orderIndex: Int

  data class Card(val card: PinnedCard) : TopLevelEntry {
    override val key: String
      get() = "card:${card.cardKey.ifEmpty { card.card.primaryEntityId }}"

    override val orderIndex: Int
      get() = card.orderIndex
  }

  data class Section(val section: PinnedSection) : TopLevelEntry {
    override val key: String
      get() = "section:${section.sectionKey.ifEmpty { section.title }}"

    override val orderIndex: Int
      get() = section.orderIndex
  }
}

@Composable
private fun DemoBanner() {
  Box(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "Demo data from your phone",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.tertiary,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun EmptyPinned() {
  Box(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = WEAR_EMPTY_PINNED,
      textAlign = TextAlign.Center,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * Opens Terrazzo on the phone, where everything the watch shows is chosen: the watch only views.
 */
@Composable
internal fun OpenOnPhoneButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Button(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    colors = ButtonDefaults.filledTonalButtonColors(),
  ) {
    Text("Open on phone")
  }
}

/** The pinned list with nothing pinned: where to choose what the watch shows. */
internal const val WEAR_EMPTY_PINNED =
  "Nothing on your watch yet.\nOn your phone, long-press a card and turn on Show on watch, or " +
    "tap a section's pin."

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
