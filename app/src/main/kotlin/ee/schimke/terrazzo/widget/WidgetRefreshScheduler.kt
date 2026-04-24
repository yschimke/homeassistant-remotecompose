package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ee.schimke.terrazzo.terrazzoGraph
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Entry point for scheduling [WidgetRefreshWorker] runs.
 *
 * Two cadences today:
 *   - [scheduleDemo] — chained `OneTimeWorkRequest`s at minute-scale
 *     intervals so demo widgets tick. Each worker re-enqueues itself
 *     while demo mode stays on, surviving process death.
 *   - TODO `scheduleLive` — a `PeriodicWorkRequest` at the WorkManager
 *     15-min floor to refresh cached HA state for live widgets.
 *
 * Unique work names keep the two cadences independent; toggling demo
 * on/off never collides with the live refresh.
 */
class WidgetRefreshScheduler(private val context: Context) {

    fun scheduleDemo(delayMinutes: Long = WidgetRefreshWorker.DEFAULT_DEMO_INTERVAL_MINUTES) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    WidgetRefreshWorker.KEY_CHAIN_DEMO to true,
                    WidgetRefreshWorker.KEY_CHAIN_DELAY_MINUTES to delayMinutes,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DEMO_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelDemo() {
        WorkManager.getInstance(context).cancelUniqueWork(DEMO_WORK_NAME)
    }

    /**
     * Broadcast `ACTION_APPWIDGET_UPDATE` to every pinned widget. Used
     * after a preference that affects widget rendering changes (theme,
     * dark mode) so existing pinned widgets re-capture a document
     * using the new palette.
     */
    suspend fun refreshAllNow() {
        val ids = context.terrazzoGraph().widgetStore.installed.first()
            .map { it.widgetId }
            .toIntArray()
        if (ids.isEmpty()) return
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
            component = ComponentName(context, TerrazzoWidgetProvider::class.java)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }

    companion object {
        const val DEMO_WORK_NAME = "terrazzo.widgetRefresh.demo"
    }
}
