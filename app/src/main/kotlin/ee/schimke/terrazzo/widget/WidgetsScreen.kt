package ee.schimke.terrazzo.widget

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
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.dashboard.historyTitle

/**
 * Home screen widgets, reached via the dashboard top-bar's overflow menu: the cards this app has on
 * the launcher, against the [WidgetStore.MAX_WIDGETS] cap, and how to add or remove one. The
 * launcher owns placement and removal, so the list is read-only.
 */
@Composable
fun WidgetsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val store = LocalTerrazzoGraph.current.widgetStore
  val installed by store.installed.collectAsState(initial = emptyList())
  // Drop rows for widgets the user has since removed from the launcher before listing them.
  LaunchedEffect(Unit) { WidgetSync.reconcile(context.applicationContext) }
  WidgetsContent(widgets = installed.map { it.card.historyTitle() }, onBack = onBack)
}

/**
 * The screen's content for [widgets] (one title each), apart from the store so a preview can draw
 * it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetsContent(widgets: List<String>, onBack: () -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Home screen widgets") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { padding ->
    if (widgets.isEmpty()) {
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
              "to ${WidgetStore.MAX_WIDGETS}.",
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
      item {
        Text(
          "${widgets.size} of ${WidgetStore.MAX_WIDGETS} in use. To remove one, long-press it " +
            "on your home screen.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
      items(widgets) { title ->
        ListItem(
          headlineContent = { Text(title) },
          leadingContent = { Icon(Icons.Outlined.Widgets, contentDescription = null) },
        )
      }
    }
  }
}
