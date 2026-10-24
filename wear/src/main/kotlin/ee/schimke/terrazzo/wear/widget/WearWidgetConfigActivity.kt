package ee.schimke.terrazzo.wear.widget

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetManager
import androidx.lifecycle.lifecycleScope
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wear.sync.syncWidgetStateFromDataLayer
import ee.schimke.terrazzo.wear.ui.WearAppSurface
import ee.schimke.terrazzo.wear.ui.WearCardLook
import ee.schimke.terrazzo.wear.ui.WearWidgetCardPicker
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The configuration activity of the Terrazzo widgets: chooses the card one widget instance shows,
 * from the cards the phone shows on the watch ([widgetCardOptions]).
 *
 * The Glance Wear contract: launched with [GlanceWearWidget.EXTRA_CONFIG_WIDGET_ID] (the widget's
 * [androidx.glance.wear.core.WidgetInstanceId.id]) and
 * [GlanceWearWidget.EXTRA_CONFIG_PROVIDER_COMPONENT] (its service), under the
 * [GlanceWearWidget.CATEGORY_PROVIDER_CONFIG] category. Choosing a card saves it
 * ([chooseWidgetCard]), redraws that widget, and finishes with [Activity.RESULT_OK]; Back finishes
 * with [Activity.RESULT_CANCELED] and changes nothing.
 *
 * How a widget host finds this activity isn't documented by Glance Wear: the manifest names its
 * action on each provider service as tile providers do (`PROVIDER_CONFIG_ACTION`), best effort. The
 * guaranteed path is the Wear app's own Widgets screen
 * ([ee.schimke.terrazzo.wear.ui.WearWidgetsScreen]), which opens the same picker.
 */
class WearWidgetConfigActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setResult(RESULT_CANCELED)
    val instanceId = intent?.getIntExtra(GlanceWearWidget.EXTRA_CONFIG_WIDGET_ID, NO_ID) ?: NO_ID
    if (instanceId == NO_ID) {
      finish()
      return
    }
    val provider = intent?.providerComponent()
    val large = provider?.className == CardLargeWidgetService::class.java.name
    val store = WearOfflineStore(applicationContext)
    val prefs = WearPrefs(applicationContext)
    val look = runBlocking { WearCardLook(prefs.themeStyle.first(), prefs.cardStyle.first()) }
    var options by
      mutableStateOf(widgetCardOptions(store.readPinned(), store.readSections()).forWidget(large))
    var values by mutableStateOf<Map<String, EntityValue>>(store.readValues()?.values.orEmpty())
    val chosenKey = WearWidgetCardStore(applicationContext).read(instanceId)?.cardKey
    // The watch app may not have been running to keep the phone's latest cards: read them from
    // the Data Layer, briefly, and show them once there.
    lifecycleScope.launch {
      runCatching {
        withTimeoutOrNull(SYNC_TIMEOUT_MS) {
          syncWidgetStateFromDataLayer(applicationContext, store)
        }
      }
      options = widgetCardOptions(store.readPinned(), store.readSections()).forWidget(large)
      values = store.readValues()?.values.orEmpty()
    }

    setContent {
      BackHandler { finish() }
      WearAppSurface(look) {
        WearWidgetCardPicker(
          options = options,
          values = values,
          chosenKey = chosenKey,
          onChoose = { option ->
            lifecycleScope.launch {
              chooseWidgetCard(applicationContext, instanceId, provider, option)
              setResult(RESULT_OK)
              finish()
            }
          },
          onCancel = { finish() },
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun android.content.Intent.providerComponent(): ComponentName? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getParcelableExtra(
        GlanceWearWidget.EXTRA_CONFIG_PROVIDER_COMPONENT,
        ComponentName::class.java,
      )
    } else {
      getParcelableExtra(GlanceWearWidget.EXTRA_CONFIG_PROVIDER_COMPONENT)
    }

  private companion object {
    const val NO_ID = Int.MIN_VALUE
    const val SYNC_TIMEOUT_MS = 3_000L
  }
}

/**
 * Saves [option] as the card widget [instanceId] shows (a copy of it too, so the widget keeps it if
 * the phone later stops sending it), then asks the host to redraw that widget.
 */
suspend fun chooseWidgetCard(
  context: Context,
  instanceId: Int,
  provider: ComponentName?,
  option: WidgetCardOption,
) {
  val app = context.applicationContext
  WearWidgetCardStore(app)
    .write(instanceId, WearWidgetCard(option.cardKey, option.card, System.currentTimeMillis()))
  refreshCardWidget(app, provider, instanceId)
}

/** A Terrazzo widget placed on the watch: its instance id, provider and size. */
data class PlacedWidget(val instanceId: Int, val provider: ComponentName, val large: Boolean)

/**
 * The Terrazzo widgets placed on the watch, as the system lists them; empty when it can't (an older
 * Wear OS, or no widget host).
 */
suspend fun placedCardWidgets(context: Context): List<PlacedWidget> {
  val small = CardSmallWidgetService::class.java.name
  val large = CardLargeWidgetService::class.java.name
  return runCatching { GlanceWearWidgetManager(context.applicationContext).fetchActiveWidgets() }
    .getOrDefault(emptyList())
    .filter {
      it.provider.packageName == context.packageName && it.provider.className in setOf(small, large)
    }
    .map { PlacedWidget(it.instanceId.id, it.provider, large = it.provider.className == large) }
    .distinctBy { it.instanceId }
    .sortedBy { it.instanceId }
}
