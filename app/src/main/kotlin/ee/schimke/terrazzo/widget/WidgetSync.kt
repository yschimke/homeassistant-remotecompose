package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import ee.schimke.terrazzo.terrazzoGraph

/**
 * Keeps `WidgetStore` in sync with the launcher's actual widget set.
 *
 * The store is written when a card is pinned ([WidgetInstallReceiver]) and the install cap
 * (`WidgetStore.MAX_WIDGETS`) is computed purely from its rows. The launcher — not the store — owns
 * the real lifecycle: a widget can disappear (the user drags it off the home screen, the launcher's
 * data is cleared, a temporary install is dropped) without [TerrazzoWidgetProvider.onDeleted] ever
 * running, e.g. because the app wasn't alive at removal time.
 *
 * [reconcile] treats `AppWidgetManager.getAppWidgetIds()` as the source of truth and prunes any
 * store row whose id the launcher no longer knows, so the "N of 5 slots free" count reflects
 * reality and a removed widget stops burning a slot.
 */
object WidgetSync {

  /** Live widget IDs hosted by the single launcher provider. */
  fun liveWidgetIds(context: Context): Set<Int> =
    AppWidgetManager.getInstance(context)
      .getAppWidgetIds(LauncherWidgetSpec.componentName(context))
      .toSet()

  /** Prunes orphaned `WidgetStore` rows; returns the number removed. */
  suspend fun reconcile(context: Context): Int =
    context.terrazzoGraph().widgetStore.retainOnly(liveWidgetIds(context))
}
