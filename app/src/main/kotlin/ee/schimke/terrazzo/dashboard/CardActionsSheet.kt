package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.formatState
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.ui.LocalCardStyle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything a long-press on a dashboard card can do, in one place. The header says what the card
 * is and where it already lives (home screen, watch, its own style), so each action reads in
 * context: "Add to home screen" when it isn't there yet, a switch for the watch rather than a
 * button that may pin or unpin, the style it is drawn in now. Navigating actions close the sheet;
 * the watch switch flips in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardActionsSheet(
  baseUrl: String,
  dashboardUrlPath: String,
  card: CardConfig,
  snapshot: HaSnapshot,
  onAddToHomeScreen: () -> Unit,
  onChangeStyle: () -> Unit,
  onShowHistory: (() -> Unit)?,
  onOpenInStudio: () -> Unit,
  onDismiss: () -> Unit,
  onMonitor: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val graph = LocalTerrazzoGraph.current
  val pinStore = graph.pinStore
  val scope = rememberCoroutineScope()
  val cardData = remember(card) { card.toPinnedData() }
  val pinKey =
    remember(baseUrl, dashboardUrlPath, cardData.rawJson) {
      PinStore.cardKey(baseUrl, dashboardUrlPath, cardData.rawJson)
    }
  val onWatch by pinStore.isCardPinned(pinKey).collectAsState(initial = false)
  val installed by graph.widgetStore.installed.collectAsState(initial = emptyList())
  val overrides by
    remember(context) { CardDesignOverrideStore.of(context).overrides }.collectAsState()
  val entityIds = remember(card) { card.historyEntityIds() }
  val state =
    CardActionsState(
      title = card.historyTitle(),
      stateLabel = card.stateLabel(snapshot),
      homeScreenCount = installed.count { it.baseUrl == baseUrl && it.card == card },
      widgetsInUse = installed.size,
      onWatch = onWatch,
      style = cardStyleLabel(card, overrides, LocalCardStyle.current),
      hasHistory = onShowHistory != null && entityIds.isNotEmpty(),
      homeAssistantUrl =
        entityIds
          .firstOrNull()
          ?.takeUnless { DemoData.isDemo(baseUrl) }
          ?.let { "${baseUrl.trimEnd('/')}/history?entity_id=$it" },
      canMonitor = onMonitor != null,
    )
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    fun closeThen(action: () -> Unit): () -> Unit = {
      onDismiss()
      action()
    }
    CardActions(
      state = state,
      onAddToHomeScreen = closeThen(onAddToHomeScreen),
      onWatchChange = { show ->
        scope.launch {
          if (show)
            pinStore.pinCard(
              MobilePinnedCard(
                key = pinKey,
                baseUrl = baseUrl,
                dashboardUrlPath = dashboardUrlPath,
                card = cardData,
              )
            )
          else pinStore.unpinCard(pinKey)
        }
      },
      onChangeStyle = closeThen(onChangeStyle),
      onShowHistory = closeThen { onShowHistory?.invoke() },
      onOpenInHomeAssistant =
        closeThen {
          state.homeAssistantUrl?.let { url ->
            runCatching {
              context.startActivity(
                android.content
                  .Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
              )
            }
          }
        },
      onOpenInStudio = closeThen(onOpenInStudio),
      onMonitor = closeThen { onMonitor?.invoke() },
    )
  }
}

/** What the sheet shows about a card; split from the stores so a preview can draw any state. */
data class CardActionsState(
  val title: String,
  val stateLabel: String? = null,
  /** Launcher widgets already showing this card. */
  val homeScreenCount: Int = 0,
  /** Launcher widgets in use across every card, against [WidgetStore.MAX_WIDGETS]. */
  val widgetsInUse: Int = 0,
  val onWatch: Boolean = false,
  /** The card style this card draws in, and whether that is its own choice. */
  val style: CardStyleLabel = CardStyleLabel(HaUberTheme.Classic.displayName, custom = false),
  val hasHistory: Boolean = true,
  val homeAssistantUrl: String? = null,
  val canMonitor: Boolean = false,
)

data class CardStyleLabel(val name: String, val custom: Boolean)

/** The sheet's content, apart from the sheet so a preview can draw it. */
@Composable
fun CardActions(
  state: CardActionsState,
  onAddToHomeScreen: () -> Unit,
  onWatchChange: (Boolean) -> Unit,
  onChangeStyle: () -> Unit,
  onShowHistory: () -> Unit,
  onOpenInHomeAssistant: () -> Unit,
  onOpenInStudio: () -> Unit,
  onMonitor: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
    Column(
      Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        state.title,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      state.stateLabel?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    PlacementChips(state, onAddToHomeScreen, onWatchChange)

    val widgetsFull = state.widgetsInUse >= WidgetStore.MAX_WIDGETS
    Action(
      Icons.Outlined.Widgets,
      if (state.homeScreenCount > 0) "Add another to home screen" else "Add to home screen",
      when {
        widgetsFull ->
          "All ${WidgetStore.MAX_WIDGETS} widgets in use · remove one from your launcher"
        else ->
          "A live widget · ${WidgetStore.MAX_WIDGETS - state.widgetsInUse} of " +
            "${WidgetStore.MAX_WIDGETS} left"
      },
      onAddToHomeScreen,
      enabled = !widgetsFull,
    )
    ListItem(
      headlineContent = { Text("Show on watch") },
      supportingContent = { Text("Pinned cards open from the watch app's home") },
      leadingContent = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
      trailingContent = { Switch(checked = state.onWatch, onCheckedChange = null) },
      modifier =
        Modifier.fillMaxWidth()
          .toggleable(value = state.onWatch, role = Role.Switch, onValueChange = onWatchChange),
    )
    Action(
      Icons.Outlined.Palette,
      "Card style",
      if (state.style.custom) state.style.name else "${state.style.name} · same as your dashboard",
      onChangeStyle,
    )
    if (state.hasHistory) {
      Action(Icons.Outlined.History, "History", "How this card's values changed", onShowHistory)
    }

    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    if (state.homeAssistantUrl != null) {
      SecondaryAction(
        Icons.AutoMirrored.Outlined.OpenInNew,
        "Open in Home Assistant",
        onOpenInHomeAssistant,
      )
    }
    if (state.canMonitor) {
      SecondaryAction(Icons.Outlined.Timer, "Watch live for 15 minutes", onMonitor)
    }
    SecondaryAction(Icons.Outlined.Brush, "Redesign a copy in Card Studio", onOpenInStudio)
  }
}

/**
 * Where the card already shows, as chips under the title; each opens the matching action. Only the
 * places it actually is: a card on no home screen, no watch and in the dashboard's style shows
 * none.
 */
@Composable
private fun PlacementChips(
  state: CardActionsState,
  onAddToHomeScreen: () -> Unit,
  onWatchChange: (Boolean) -> Unit,
) {
  val chips = buildList {
    if (state.homeScreenCount > 0) {
      add(
        Triple(
          Icons.Outlined.Widgets,
          if (state.homeScreenCount == 1) "On home screen"
          else "On home screen ×${state.homeScreenCount}",
          onAddToHomeScreen,
        )
      )
    }
    if (state.onWatch) add(Triple(Icons.Outlined.PushPin, "On watch", { onWatchChange(false) }))
  }
  if (chips.isEmpty()) return
  FlowRow(
    Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    chips.forEach { (icon, label, onClick) ->
      AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        colors =
          AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          ),
        border = null,
      )
    }
  }
}

@Composable
private fun Action(
  icon: ImageVector,
  label: String,
  supporting: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  val alpha = if (enabled) 1f else 0.38f
  ListItem(
    headlineContent = { Text(label) },
    supportingContent = { Text(supporting) },
    leadingContent = { Icon(icon, contentDescription = null) },
    colors =
      ListItemDefaults.colors(
        headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
      ),
    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
  )
}

/** A less frequent action: one line, no explanation, so the main four stay the focus. */
@Composable
private fun SecondaryAction(icon: ImageVector, label: String, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
    leadingContent = { Icon(icon, contentDescription = null) },
    colors = ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.onSurfaceVariant),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
  )
}

/** The card's primary entity and its formatted state, e.g. "Front door · Closed". */
private fun CardConfig.stateLabel(snapshot: HaSnapshot): String? {
  val entityId = historyEntityIds().firstOrNull() ?: return null
  val entity = snapshot.states[entityId] ?: return null
  val name = entity.attributes["friendly_name"]?.jsonPrimitive?.contentOrNull ?: entityId
  val state = formatState(entity, snapshot)
  return if (name == historyTitle()) state else "$name · $state"
}

/** The style [card] draws in: its own override, its type's, or the user's [dashboard] style. */
internal fun cardStyleLabel(
  card: CardConfig,
  overrides: CardDesignOverrides,
  dashboard: HaUberTheme,
): CardStyleLabel {
  val own = overrides.at(CardDesignScope.Card, card)?.cardStyle
  val byType = overrides.at(CardDesignScope.CardType, card)?.cardStyle
  return when {
    own != null -> CardStyleLabel(HaUberTheme.byId(own).displayName, custom = true)
    byType != null -> CardStyleLabel(HaUberTheme.byId(byType).displayName, custom = true)
    else -> CardStyleLabel(dashboard.displayName, custom = false)
  }
}
