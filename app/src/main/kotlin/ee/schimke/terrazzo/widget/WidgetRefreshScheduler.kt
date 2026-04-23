package ee.schimke.terrazzo.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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

    companion object {
        const val DEMO_WORK_NAME = "terrazzo.widgetRefresh.demo"
    }
}
