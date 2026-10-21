package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.ui.LocalCardStyle
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import kotlinx.coroutines.launch

/**
 * Draws one card, or every card of its type, in a card style other than the dashboard's. The card
 * itself sits at the top and redraws as a style is picked, so the choice is judged on the real card
 * rather than a style name. The choice is kept on this device ([CardDesignOverrideStore]); the
 * dashboard and launcher widgets pick it up at once, and the design sync fetches the style's
 * designs. Wear widgets keep the watch's own card style for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardStyleSheet(
  session: HaSession,
  card: CardConfig,
  snapshot: HaSnapshot,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val store = remember(context) { CardDesignOverrideStore.of(context) }
  val overrides by store.overrides.collectAsState()
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    CardStylePicker(
      card = card,
      overrides = overrides,
      dashboardStyle = LocalCardStyle.current,
      onPick = { at, cardStyle ->
        scope.launch {
          val keep = overrides.at(at, card)
          store.set(at, card, CardDesignOverride(cardStyle = cardStyle, variant = keep?.variant))
          // A launcher widget of this card draws in its style too.
          WidgetRefreshScheduler(context.applicationContext).refreshAllNow()
        }
      },
      onDone = onDismiss,
    ) {
      DashboardCardPreview(session, card, snapshot)
    }
  }
}

/**
 * The picker: the card [preview], which of "this card" or "every card of its type" a pick applies
 * to, then one radio list of styles led by "follow the dashboard". Apart from the sheet so a
 * preview can draw it.
 */
@Composable
internal fun CardStylePicker(
  card: CardConfig,
  overrides: CardDesignOverrides,
  dashboardStyle: HaUberTheme,
  onPick: (CardDesignScope, String?) -> Unit,
  onDone: () -> Unit,
  modifier: Modifier = Modifier,
  preview: @Composable () -> Unit,
) {
  // Open on the scope that already decides this card's style, so the selected row is the one in
  // use.
  var at by
    remember(card) {
      mutableStateOf(
        if (
          overrides.at(CardDesignScope.Card, card) == null &&
            overrides.at(CardDesignScope.CardType, card) != null
        )
          CardDesignScope.CardType
        else CardDesignScope.Card
      )
    }
  val typeLabel = card.type.removePrefix("custom:").replace('-', ' ')
  val current = overrides.at(at, card)?.cardStyle
  // With no style of its own, a card follows its type's override when there is one.
  val inherited =
    if (at == CardDesignScope.Card) overrides.at(CardDesignScope.CardType, card)?.cardStyle
    else null
  val followLabel =
    if (inherited != null) "Same as other $typeLabel cards" else "Same as your dashboard"
  val followStyle = HaUberTheme.byId(inherited ?: dashboardStyle.id).displayName

  Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
    Column(
      Modifier.padding(horizontal = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Card style", style = MaterialTheme.typography.titleLarge)
      OutlinedCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
          preview()
        }
      }
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(
            CardDesignScope.Card to "This card",
            CardDesignScope.CardType to "All $typeLabel cards",
          )
          .forEachIndexed { index, (value, label) ->
            SegmentedButton(
              selected = at == value,
              onClick = { at = value },
              shape = SegmentedButtonDefaults.itemShape(index, 2),
              label = { Text(label, maxLines = 1) },
            )
          }
      }
    }
    Column(
      Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()
    ) {
      StyleOption(followLabel, followStyle, selected = current == null) { onPick(at, null) }
      HaUberTheme.all.forEach { theme ->
        StyleOption(theme.displayName, null, selected = current == theme.id) {
          onPick(at, theme.id)
        }
      }
    }
    Text(
      "Kept on this phone. Home screen widgets follow it; your watch keeps its own style.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
      Text("Done")
    }
  }
}

@Composable
private fun StyleOption(
  label: String,
  supporting: String?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  ListItem(
    headlineContent = { Text(label) },
    supportingContent = supporting?.let { { Text(it) } },
    leadingContent = { RadioButton(selected = selected, onClick = null) },
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
  )
}
