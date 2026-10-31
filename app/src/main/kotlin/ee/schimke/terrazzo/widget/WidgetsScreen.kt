package ee.schimke.terrazzo.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.wearsync.WatchWidget
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.dashboard.historyTitle

/**
 * Widgets, reached via the dashboard top-bar's overflow menu: the cards this app has on the
 * launcher, against the [WidgetStore.MAX_WIDGETS] cap, and the Terrazzo widgets placed on paired
 * watches. A home screen row opens the widget's editor ([WidgetConfigureActivity]); a watch row
 * chooses the card that widget shows. The launcher and the watch own placement and removal.
 */
@Composable
fun WidgetsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val graph = LocalTerrazzoGraph.current
  val installed by graph.widgetStore.installed.collectAsState(initial = emptyList())
  val watchWidgets by graph.wearSyncManager.watchWidgets.collectAsState()
  val wearable by graph.wearSyncManager.wearableAvailable.collectAsState()
  // Drop rows for widgets the user has since removed from the launcher before listing them.
  LaunchedEffect(Unit) { WidgetSync.reconcile(context.applicationContext) }
  WidgetsContent(
    widgets =
      installed.map {
        InstalledWidget(
          it.card.historyTitle(),
          cardTypeLabel(it.card.type),
          it.widgetId,
          liveUpdates = it.liveUpdates,
        )
      },
    watchWidgets = watchWidgets,
    watchHelp = wearable,
    onChooseWatchCard = { widget, cardKey ->
      graph.wearSyncManager.chooseWatchWidgetCard(widget.nodeId, widget.instanceId, cardKey)
    },
    onWatchLiveUpdates = { widget, on ->
      graph.wearSyncManager.setWatchWidgetLive(widget.nodeId, widget.instanceId, on)
    },
    onBack = onBack,
    onEdit = { context.startActivity(WidgetConfigureActivity.intent(context, it.widgetId)) },
  )
}

/**
 * One installed widget's row: the card's [title], the [kind] of card it draws, and whether it has
 * live updates on.
 */
internal data class InstalledWidget(
  val title: String,
  val kind: String,
  val widgetId: Int = 0,
  val liveUpdates: Boolean = false,
)

/**
 * A card type as a row's supporting text: `weather-forecast` reads "Weather forecast card", and a
 * `custom:` card keeps its own name ("Bambu print status card").
 */
internal fun cardTypeLabel(type: String): String {
  val words =
    type.removePrefix("custom:").replace('-', ' ').replace('_', ' ').trim().removeSuffix(" card")
  if (words.isEmpty() || words == "card") return "Card"
  return words.replaceFirstChar { it.uppercaseChar() } + " card"
}

/**
 * What a watch widget's row says under its card: its size, and which watch when [named] (more than
 * one watch has widgets).
 */
internal fun watchWidgetLabel(widget: WatchWidget, named: Boolean): String {
  val size = if (widget.large) "Large watch widget" else "Small watch widget"
  return if (named && widget.nodeName.isNotEmpty()) "$size · ${widget.nodeName}" else size
}

/**
 * The screen's content for [widgets] and [watchWidgets], apart from the stores so a preview can
 * draw it. Tapping a watch widget offers its cards; [onChooseWatchCard] takes the choice. Each
 * watch widget's switch turns its live updates on or off ([onWatchLiveUpdates]). With [watchHelp]
 * (this phone can reach a watch) and no watch widget yet, the Watch section says how to add one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetsContent(
  widgets: List<InstalledWidget>,
  onBack: () -> Unit,
  onEdit: (InstalledWidget) -> Unit = {},
  watchWidgets: List<WatchWidget> = emptyList(),
  watchHelp: Boolean = false,
  onChooseWatchCard: (WatchWidget, String) -> Unit = { _, _ -> },
  onWatchLiveUpdates: (WatchWidget, Boolean) -> Unit = { _, _ -> },
) {
  var choosing by remember { mutableStateOf<WatchWidget?>(null) }
  choosing?.let { widget ->
    WatchWidgetCardDialog(
      widget = widget,
      onChoose = { cardKey ->
        onChooseWatchCard(widget, cardKey)
        choosing = null
      },
      onDismiss = { choosing = null },
    )
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Widgets") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { padding ->
    if (widgets.isEmpty() && watchWidgets.isEmpty()) {
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          imageVector = Icons.Outlined.Widgets,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(56.dp),
        )
        Text(
          text = "No widgets yet",
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
        )
        Text(
          text =
            "Long-press a card on a dashboard and choose Add to home screen. You can have up " +
              "to ${WidgetStore.MAX_WIDGETS}." +
              if (watchHelp) "\n\n$WATCH_WIDGET_STEPS" else "",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      return@Scaffold
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
      contentPadding = PaddingValues(vertical = 8.dp),
    ) {
      item { SectionHeader("Home screen") }
      item {
        Text(
          if (widgets.isEmpty()) {
            "Long-press a card on a dashboard and choose Add to home screen. You can have up " +
              "to ${WidgetStore.MAX_WIDGETS}."
          } else {
            "${widgets.size} of ${WidgetStore.MAX_WIDGETS} in use. Tap one to edit it. To remove " +
              "one, long-press it on your home screen."
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
      items(widgets) { widget ->
        ListItem(
          headlineContent = { Text(widget.title) },
          supportingContent = {
            Text(if (widget.liveUpdates) "${widget.kind} · Live updates" else widget.kind)
          },
          leadingContent = { Icon(Icons.Outlined.Widgets, contentDescription = null) },
          modifier = Modifier.clickable(onClickLabel = "Edit widget") { onEdit(widget) },
        )
      }
      if (watchWidgets.isEmpty() && watchHelp) {
        item { SectionHeader("Watch") }
        item {
          Text(
            WATCH_WIDGET_STEPS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
      }
      if (watchWidgets.isNotEmpty()) {
        item { SectionHeader("Watch") }
        item {
          Text(
            "Tap a widget to choose its card. Turn on its switch for live updates while the app " +
              "is closed. To add or remove one, swipe left from the watch face to the end.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
        val named = watchWidgets.map { it.nodeId }.distinct().size > 1
        items(watchWidgets, key = { "${it.nodeId}/${it.instanceId}" }) { widget ->
          val title = widget.options.firstOrNull { it.cardKey == widget.cardKey }?.title
          ListItem(
            headlineContent = { Text(title ?: "No card yet") },
            supportingContent = { Text(watchWidgetLabel(widget, named)) },
            leadingContent = { Icon(Icons.Outlined.Watch, contentDescription = null) },
            trailingContent = {
              Switch(
                checked = widget.liveUpdates,
                onCheckedChange = { onWatchLiveUpdates(widget, it) },
                enabled = widget.cardKey != null,
                modifier = Modifier.semantics { contentDescription = "Live updates" },
              )
            },
            modifier =
              Modifier.clickable(enabled = widget.options.isNotEmpty()) { choosing = widget },
          )
        }
      }
    }
  }
}

/**
 * How to put a card on the watch face, in the words the watch's widget picker uses: its providers
 * are labelled "Terrazzo".
 */
internal const val WATCH_WIDGET_STEPS =
  "On your watch, swipe left from the watch face to the end, tap Add widget and choose " +
    "Terrazzo. Then pick its card here: any card you show on your watch."

@Composable
private fun SectionHeader(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
  )
}

/** The cards [widget] can show, its current one selected. */
@Composable
private fun WatchWidgetCardDialog(
  widget: WatchWidget,
  onChoose: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (widget.large) "Large watch widget" else "Small watch widget") },
    text = {
      LazyColumn {
        items(widget.options, key = { it.cardKey }) { option ->
          ListItem(
            headlineContent = { Text(option.title) },
            leadingContent = {
              RadioButton(selected = option.cardKey == widget.cardKey, onClick = null)
            },
            modifier = Modifier.clickable { onChoose(option.cardKey) },
          )
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
