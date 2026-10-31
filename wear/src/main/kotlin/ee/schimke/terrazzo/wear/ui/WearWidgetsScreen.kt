package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.wear.widget.PlacedWidget
import ee.schimke.terrazzo.wear.widget.WearWidgetCardStore
import ee.schimke.terrazzo.wear.widget.currentWidgetCard
import ee.schimke.terrazzo.wear.widget.publishPlacedWidgets
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet

/** A row of [WearWidgetsContent]: a placed widget and the title of the card it shows, if any. */
internal data class WidgetRow(val widget: PlacedWidget, val cardTitle: String?)

/**
 * The Wear app's Widgets screen: the Terrazzo widgets placed on the watch, each with the card it
 * shows. Read-only: the phone chooses each widget's card. Opening it also tells the phone what is
 * placed ([publishPlacedWidgets]), so a widget the phone hasn't heard of yet gets its card.
 *
 * [widgetCardsRevision] changes when the phone's choices arrive, so the rows re-read them.
 */
@Composable
fun WearWidgetsScreen(
  pinned: PinnedCardSet,
  sections: PinnedSectionSet,
  widgetCardsRevision: Long,
  modifier: Modifier = Modifier,
  onOpenOnPhone: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val store = remember(context) { WearWidgetCardStore(context.applicationContext) }
  var placed by remember { mutableStateOf<List<PlacedWidget>?>(null) }
  LaunchedEffect(Unit) { placed = publishPlacedWidgets(context).orEmpty() }
  val rows =
    remember(placed, pinned, sections, widgetCardsRevision) {
      placed?.map { widget ->
        WidgetRow(
          widget,
          store.read(widget.instanceId)?.let { cardLabel(currentWidgetCard(it, pinned, sections)) },
        )
      }
    }
  WearWidgetsContent(rows = rows, modifier = modifier, onOpenOnPhone = onOpenOnPhone)
}

/**
 * [WearWidgetsScreen]'s list: [rows] null while the placed widgets load. With none placed, the
 * steps to add one, in the words the watch's widget picker uses ([WIDGET_PICKER_LABEL]).
 */
@Composable
internal fun WearWidgetsContent(
  rows: List<WidgetRow>?,
  modifier: Modifier = Modifier,
  onOpenOnPhone: (() -> Unit)? = null,
) {
  WearListScreen(modifier = modifier, spacing = 6.dp) {
    item { ListHeader { Text("Widgets") } }
    when {
      rows == null -> Unit
      rows.isEmpty() ->
        item {
          Text(
            text = WIDGETS_EMPTY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          )
        }
      else ->
        items(rows, key = { it.widget.instanceId }) { row ->
          Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
              text = if (row.widget.large) "Large widget" else "Small widget",
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = row.cardTitle ?: "No card yet",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
            )
          }
        }
    }
    if (!rows.isNullOrEmpty()) {
      item {
        Text(
          text = WIDGETS_CHOOSE_ON_PHONE,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
      }
    }
    onOpenOnPhone?.let { open -> item { OpenOnPhoneButton(open) } }
  }
}

/** The name the watch's widget picker lists the widgets under: the providers' `label`. */
internal const val WIDGET_PICKER_LABEL = "Terrazzo"

/** The Widgets screen's text with no widget placed: how to add one. */
internal const val WIDGETS_EMPTY =
  "Swipe left from your watch face to the end, tap Add widget and choose $WIDGET_PICKER_LABEL." +
    "\n\nThen pick its card on your phone, under Widgets."

/** Under the Widgets screen's list: where the cards are chosen. */
internal const val WIDGETS_CHOOSE_ON_PHONE =
  "Each widget's card is chosen on your phone, under Widgets."

/** What a list names [card] by: its title, else its entity, else its type. */
internal fun cardLabel(card: CardSummary): String =
  card.title.ifEmpty { card.primaryEntityId }.ifEmpty { card.type }.ifEmpty { "Card" }
