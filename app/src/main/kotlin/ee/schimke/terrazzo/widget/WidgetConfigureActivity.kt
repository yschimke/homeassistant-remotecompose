package ee.schimke.terrazzo.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.dashboard.CardDesignOverrideStore
import ee.schimke.terrazzo.terrazzoGraph
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.ui.toThemeStyle
import ee.schimke.terrazzo.ui.toUberTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Changes a placed launcher widget: what the launcher opens from the widget's long-press
 * ("Reconfigure", or the widget's settings), as the provider metadata's `configure` activity, and
 * what a row of the app's Widgets screen opens.
 *
 * The preview draws the widget at the size the launcher reports for it ([WidgetSizing]), not at a
 * size picked here: a placed widget's size is the launcher's. Saving rewrites the widget's row in
 * [WidgetStore] and re-renders it.
 *
 * Placing a widget needs no configuration (`configuration_optional`): the app pins it with its card
 * already chosen. Exported because the launcher starts it; a widget id that isn't one of this app's
 * placed widgets closes it.
 */
class WidgetConfigureActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val widgetId =
      intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    // Backing out leaves the widget as it was.
    setResult(Activity.RESULT_CANCELED, resultIntent(widgetId))
    val manager = AppWidgetManager.getInstance(this)
    val ours =
      manager.getAppWidgetInfo(widgetId)?.provider == LauncherWidgetSpec.componentName(this)
    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || !ours) {
      finish()
      return
    }
    enableEdgeToEdge()
    val graph = terrazzoGraph()
    lifecycleScope.launch {
      val entry = graph.widgetStore.get(widgetId)
      val snapshot =
        entry?.let {
          if (DemoData.isDemo(it.baseUrl)) DemoData.snapshot()
          else graph.offlineCache.latestSnapshot(it.baseUrl)
        } ?: HaSnapshot()
      val size = WidgetSizing.forWidgetCapture(manager, widgetId)
      setContent {
        val themePref by
          graph.preferencesStore.themeStyle.collectAsState(initial = ThemePref.Material3)
        val darkPref by
          graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)
        val cardStylePref by
          graph.preferencesStore.cardStyle.collectAsState(initial = CardStylePref.Classic)
        TerrazzoTheme(themePref.toThemeStyle(), darkPref, cardStylePref.toUberTheme()) {
          // Previews an edit in the widget's own style: a card's style override is keyed by its
          // whole config, so a renamed card would otherwise lose it until saved.
          val capture: WidgetPreviewCapture = { ctx, url, card, state, previewSize ->
            TerrazzoWidgetProvider()
              .renderPreview(ctx, url, card, state, previewSize, styleCard = entry?.card ?: card)
          }
          CompositionLocalProvider(LocalWidgetPreviewCapture provides capture) {
            WidgetConfigureScreen(
              entry = entry,
              snapshot = snapshot,
              size = size,
              onClose = ::finish,
              onSave = { card, live -> save(widgetId, entry!!, card, live) },
            )
          }
        }
      }
    }
  }

  private fun save(widgetId: Int, entry: WidgetStore.Entry, card: CardConfig, live: Boolean) {
    lifecycleScope.launch {
      // The renamed card keeps the style the user picked for it.
      val overrides = CardDesignOverrideStore.of(applicationContext)
      overrides.overrides.value.at(CardDesignScope.Card, entry.card)?.let {
        overrides.set(CardDesignScope.Card, card, it)
      }
      terrazzoGraph().widgetStore.put(widgetId, entry.baseUrl, card, live, entry.origin)
      refresh(applicationContext, widgetId)
      setResult(Activity.RESULT_OK, resultIntent(widgetId))
      finish()
    }
  }

  companion object {
    /** Opens the editor for [widgetId], from the app's own Widgets screen. */
    fun intent(context: Context, widgetId: Int): Intent =
      Intent(context, WidgetConfigureActivity::class.java)
        .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun resultIntent(widgetId: Int) =
      Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    /** Asks the provider to re-render [widgetId] from its saved row. */
    private fun refresh(context: Context, widgetId: Int) {
      context.sendBroadcast(
        Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
          .setComponent(LauncherWidgetSpec.componentName(context))
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
      )
    }
  }
}

/**
 * The editor's content: the widget at its home screen [size], its label, and whether it keeps up
 * with Home Assistant while the app is closed (live updates, off by default). With no [entry] (a
 * widget added from the system picker, which no card was chosen for) it says how to add one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetConfigureScreen(
  entry: WidgetStore.Entry?,
  snapshot: HaSnapshot,
  size: WidgetSizeDp,
  onClose: () -> Unit,
  onSave: (card: CardConfig, liveUpdates: Boolean) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Edit widget") },
        navigationIcon = {
          IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        },
      )
    }
  ) { padding ->
    Column(
      Modifier.fillMaxSize()
        .padding(padding)
        .consumeWindowInsets(padding)
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (entry == null) {
        Text(
          "This widget isn't showing a card yet. Long-press a card on a dashboard in the app " +
            "and choose Add to home screen.",
          style = MaterialTheme.typography.bodyLarge,
        )
        return@Column
      }
      val savedName = remember(entry) { entry.card.raw.string("name").orEmpty() }
      // Kept across rotation: the activity reloads the saved entry, not the draft.
      var name by rememberSaveable(entry) { mutableStateOf(savedName) }
      val edited = remember(entry, name) { entry.card.withLabel(name) }
      WidgetPreparationPreview(entry.baseUrl, edited, snapshot, fixedSize = size) {}
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Label (optional)") },
        placeholder = { Text("The entity's own name") },
        supportingText = { Text("Only this widget. Your dashboard stays as it is.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
      var live by rememberSaveable(entry) { mutableStateOf(entry.liveUpdates) }
      LiveUpdatesRow(checked = live, onCheckedChange = { live = it })
      Button(
        onClick = { onSave(edited, live) },
        enabled = edited != entry.card || live != entry.liveUpdates,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Save")
      }
    }
  }
}

/**
 * A widget's live updates switch: off, it updates while the app is running and from the routine
 * refresh ([WidgetRoutineRefreshWorker]); on, a visible change in Home Assistant also wakes the app
 * to redraw it (`docs/architecture/live-widget-updates.md`).
 */
@Composable
internal fun LiveUpdatesRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  ListItem(
    headlineContent = { Text("Live updates") },
    supportingContent = {
      Text(
        "Off, it updates while the app is running and about every 30 minutes. On, Home " +
          "Assistant wakes the app as soon as it visibly changes, which uses some battery."
      )
    },
    trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    modifier =
      Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
  )
}

/**
 * [this] card with its `name` set to [label], or with no `name` of its own when [label] is blank.
 */
internal fun CardConfig.withLabel(label: String): CardConfig {
  val trimmed = label.trim()
  if (trimmed == raw.string("name").orEmpty()) return this
  val rest = raw - "name"
  return copy(
    raw = JsonObject(if (trimmed.isEmpty()) rest else rest + ("name" to JsonPrimitive(trimmed)))
  )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
