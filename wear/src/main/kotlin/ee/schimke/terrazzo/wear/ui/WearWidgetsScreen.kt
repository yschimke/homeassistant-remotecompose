package ee.schimke.terrazzo.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.terrazzo.wear.widget.PlacedWidget
import ee.schimke.terrazzo.wear.widget.WearWidgetCardStore
import ee.schimke.terrazzo.wear.widget.chooseWidgetCard
import ee.schimke.terrazzo.wear.widget.currentWidgetCard
import ee.schimke.terrazzo.wear.widget.placedCardWidgets
import ee.schimke.terrazzo.wear.widget.widgetCardOptions
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import kotlinx.coroutines.launch

/** A row of [WearWidgetsContent]: a placed widget and the title of the card it shows, if any. */
internal data class WidgetRow(val widget: PlacedWidget, val cardTitle: String?)

/**
 * The Wear app's Widgets screen: the Terrazzo widgets placed on the watch, each with the card it
 * shows. Tapping one opens the card picker ([WearWidgetCardPicker]) for it; the choice is saved and
 * that widget redraws. This is the guaranteed way to choose a widget's card: a Wear widget has no
 * tap action, and whether the system opens
 * [ee.schimke.terrazzo.wear.widget.WearWidgetConfigActivity] when one is added is up to its host.
 */
@Composable
fun WearWidgetsScreen(
  pinned: PinnedCardSet,
  sections: PinnedSectionSet,
  values: Map<String, EntityValue>,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember(context) { WearWidgetCardStore(context.applicationContext) }
  // Bumped after a choice, so the rows re-read the store.
  var generation by remember { mutableIntStateOf(0) }
  var placed by remember { mutableStateOf<List<PlacedWidget>?>(null) }
  var editing by remember { mutableStateOf<PlacedWidget?>(null) }
  LaunchedEffect(generation) { placed = placedCardWidgets(context) }

  val options = remember(pinned, sections) { widgetCardOptions(pinned, sections) }
  val current = editing
  if (current != null) {
    BackHandler { editing = null }
    WearWidgetCardPicker(
      options = options,
      values = values,
      chosenKey = remember(current, generation) { store.read(current.instanceId)?.cardKey },
      onChoose = { option ->
        scope.launch {
          chooseWidgetCard(context, current.instanceId, current.provider, option)
          editing = null
          generation++
        }
      },
      onCancel = { editing = null },
      modifier = modifier,
    )
    return
  }
  val rows =
    remember(placed, pinned, sections, generation) {
      placed?.map { widget ->
        WidgetRow(
          widget,
          store.read(widget.instanceId)?.let { cardLabel(currentWidgetCard(it, pinned, sections)) },
        )
      }
    }
  WearWidgetsContent(rows = rows, onOpen = { editing = it }, onBack = onBack, modifier = modifier)
}

/** [WearWidgetsScreen]'s list: [rows] null while the placed widgets load. */
@Composable
internal fun WearWidgetsContent(
  rows: List<WidgetRow>?,
  onOpen: (PlacedWidget) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
          )
        }
      else ->
        items(rows, key = { it.widget.instanceId }) { row ->
          Button(
            onClick = { onOpen(row.widget) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.filledTonalButtonColors(),
          ) {
            Column {
              Text(
                text = if (row.widget.large) "Large widget" else "Small widget",
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = row.cardTitle ?: "No card chosen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
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

/** The Widgets screen's text with no Terrazzo widget placed. */
internal const val WIDGETS_EMPTY =
  "Add a Terrazzo widget from your watch's widget list, then choose its card here."
