package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.HaUberTheme
import kotlinx.coroutines.launch

/**
 * Lets the user draw one card, or every card of its type, in a card style other than their own. The
 * choice is kept on this device ([CardDesignOverrideStore]); the dashboard picks it up at once, and
 * the design sync fetches the style's designs. Widgets don't: launcher widgets follow the
 * launcher's system colours, and Wear widgets the watch's own card style.
 */
@Composable
internal fun CardDesignOverrideSection(card: CardConfig) {
  val context = LocalContext.current
  val store = remember(context) { CardDesignOverrideStore.of(context) }
  val overrides by store.overrides.collectAsState()
  val scope = rememberCoroutineScope()
  CardDesignOverridePicker(card, overrides) { at, cardStyle ->
    scope.launch {
      val keep = overrides.at(at, card)
      store.set(at, card, CardDesignOverride(cardStyle = cardStyle, variant = keep?.variant))
    }
  }
}

/** The picker itself: a row of card styles for this card, and one for every card of its type. */
@Composable
internal fun CardDesignOverridePicker(
  card: CardConfig,
  overrides: CardDesignOverrides,
  onPick: (CardDesignScope, String?) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Card style", style = MaterialTheme.typography.titleMedium)
    Text(
      "Draw this card in another style than the rest of your dashboard. Kept on this device.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    StyleRow("This card", CardDesignScope.Card, card, overrides, onPick)
    StyleRow("Every ${card.type} card", CardDesignScope.CardType, card, overrides, onPick)
  }
}

@Composable
private fun StyleRow(
  label: String,
  scope: CardDesignScope,
  card: CardConfig,
  overrides: CardDesignOverrides,
  onPick: (CardDesignScope, String?) -> Unit,
) {
  val current = overrides.at(scope, card)?.cardStyle
  // With no style of its own, a card follows its type's override when there is one; the user's
  // own style is then one of the styles below, picked as an exception.
  val inherited =
    if (scope == CardDesignScope.Card) overrides.at(CardDesignScope.CardType, card)?.cardStyle
    else null
  val unsetLabel =
    inherited?.let { "Same as every ${card.type} (${HaUberTheme.byId(it).displayName})" }
      ?: "Your style"
  Text(label, style = MaterialTheme.typography.labelLarge)
  Row(
    modifier = Modifier.horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilterChip(
      selected = current == null,
      onClick = { onPick(scope, null) },
      label = { Text(unsetLabel) },
      modifier = Modifier.semantics { contentDescription = "$label: $unsetLabel" },
    )
    HaUberTheme.all.forEach { theme ->
      FilterChip(
        selected = current == theme.id,
        onClick = { onPick(scope, theme.id) },
        label = { Text(theme.displayName) },
        modifier = Modifier.semantics { contentDescription = "$label: ${theme.displayName}" },
      )
    }
  }
}
