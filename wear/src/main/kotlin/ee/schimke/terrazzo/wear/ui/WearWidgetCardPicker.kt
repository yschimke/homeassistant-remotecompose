package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import ee.schimke.ha.rc.LocalRelativeTimeTick
import ee.schimke.ha.rc.rememberRelativeTimeTick
import ee.schimke.terrazzo.wear.widget.WidgetCardOption
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue

/**
 * Chooses the card a Terrazzo widget shows, from [options]: the cards the phone shows on the watch
 * ([ee.schimke.terrazzo.wear.widget.widgetCardOptions]), each drawn as the Wear app draws it
 * ([WearRemoteCard]), so it looks like the widget will. Tapping one calls [onChoose].
 *
 * With nothing synced it says where cards come from: the phone's "Show on watch".
 */
@Composable
internal fun WearWidgetCardPicker(
  options: List<WidgetCardOption>,
  values: Map<String, EntityValue>,
  onChoose: (WidgetCardOption) -> Unit,
  modifier: Modifier = Modifier,
  chosenKey: String? = null,
  onCancel: (() -> Unit)? = null,
) {
  WearListScreen(modifier = modifier, spacing = 6.dp) {
    item { ListHeader { Text("Choose a card") } }
    if (options.isEmpty()) {
      item {
        Text(
          text = PICKER_EMPTY,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
      }
    } else {
      items(options, key = { it.cardKey }) { option ->
        val label = cardLabel(option.card)
        Column {
          if (option.cardKey == chosenKey) {
            Text(
              text = "Current",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 8.dp),
            )
          }
          // The card plays its own document; a layer on top takes the tap, so choosing one never
          // also presses a control inside it.
          Box(Modifier.fillMaxWidth()) {
            WearRemoteCard(option.card, values)
            Box(
              Modifier.matchParentSize()
                .semantics { contentDescription = "Choose $label" }
                .clickable { onChoose(option) }
            )
          }
        }
      }
    }
    if (onCancel != null) {
      item {
        Button(
          onClick = onCancel,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
          Text("Cancel")
        }
      }
    }
  }
}

/** What a list names [card] by: its title, else its entity, else its type. */
internal fun cardLabel(card: CardSummary): String =
  card.title.ifEmpty { card.primaryEntityId }.ifEmpty { card.type }.ifEmpty { "Card" }

/** The picker's text when the phone has sent no card. */
internal const val PICKER_EMPTY = "Choose cards on your phone: Show on watch"

/**
 * The Wear app's theme and card look around [content], in an [AppScaffold] with the time: for a
 * screen outside [ee.schimke.terrazzo.wear.WearMainActivity], such as the widget configuration
 * activity.
 */
@Composable
internal fun WearAppSurface(look: WearCardLook, content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = terrazzoWearColorScheme(look.palette),
    typography = wearTypographyFor(look.palette),
  ) {
    CompositionLocalProvider(
      LocalWearCardLook provides look,
      LocalRelativeTimeTick provides rememberRelativeTimeTick(),
    ) {
      AppScaffold(timeText = { TimeText() }) { content() }
    }
  }
}
