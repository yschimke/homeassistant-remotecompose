package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import ee.schimke.terrazzo.terrazzoGraph

/**
 * Keeps `WidgetStore` in sync with the launcher's actual widget set.
 *
 * The store is written when a card is pinned ([WidgetInstallReceiver])
 * and the install cap (`WidgetStore.MAX_WIDGETS`) is computed purely
 * from its rows. The launcher — not the store — owns the real
 * lifecycle: a widget can disappear (the user drags it off the home
 * screen, the launcher's data is cleared, a temporary install is
 * dropped) without [TerrazzoWidgetProvider.onDeleted] ever running,
 * e.g. because the app wasn't alive at removal time.
 *
 * [reconcile] treats `AppWidgetManager.getAppWidgetIds()` as the source
 * of truth and prunes any store row whose id the launcher no longer
 * knows, so the "N of 5 slots free" count reflects reality and a
 * removed widget stops burning a slot.
 */
object WidgetSync {

    /**
     * Live widget ids the launcher currently hosts across every provider
     * variant we install ([WidgetSizeClass]). The variants share their
     * rendering but are distinct components, so each must be queried
     * separately and the results unioned.
     */
    fun liveWidgetIds(context: Context): Set<Int> {
        val manager = AppWidgetManager.getInstance(context)
        return buildSet {
            for (provider in providerClasses) {
                manager.getAppWidgetIds(ComponentName(context, provider)).forEach { add(it) }
            }
        }
    }

    /** Prunes orphaned `WidgetStore` rows; returns the number removed. */
    suspend fun reconcile(context: Context): Int =
        context.terrazzoGraph().widgetStore.retainOnly(liveWidgetIds(context))

    private val providerClasses = listOf(
        TerrazzoWidgetProvider::class.java,
        TerrazzoWidgetProviderSmall::class.java,
        TerrazzoWidgetProviderTall::class.java,
    )
}
