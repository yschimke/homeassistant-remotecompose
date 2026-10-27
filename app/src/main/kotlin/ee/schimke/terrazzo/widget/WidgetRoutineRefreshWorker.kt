package ee.schimke.terrazzo.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.terrazzoGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first

/**
 * The routine fallback for every widget, live updates on or off
 * (`docs/architecture/live-widget-updates.md`): every [INTERVAL_MINUTES], with a network, it reads
 * each Home Assistant with widgets on it once and redraws them, and sends the watch its values. A
 * widget without live updates then shows states at most about that old, or fresher whenever the app
 * runs ([LiveWidgetUpdates.follow]).
 *
 * Scheduled while there is a widget to refresh ([sync]); it cancels itself once there is none.
 */
class WidgetRoutineRefreshWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val servers = servers(applicationContext)
    if (servers.isEmpty()) {
      cancel(applicationContext)
      return Result.success()
    }
    for (baseUrl in servers) {
      try {
        WidgetActionRunner.withSession(applicationContext, baseUrl) { session ->
          // The app's own session already follows its states: its latest copy is as fresh.
          val snapshot = session.liveStates.value ?: session.loadSnapshot()
          LiveWidgetUpdates.redraw(applicationContext, baseUrl, snapshot)
        }
      } catch (timedOut: TimeoutCancellationException) {
        Log.w(TAG, "Timed out refreshing the widgets on $baseUrl", timedOut)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        // Unreachable now (offline, HA restarting): the next run tries again.
        Log.w(TAG, "Couldn't refresh the widgets on $baseUrl", failure)
      }
    }
    return Result.success()
  }

  companion object {
    private const val TAG = "WidgetRoutineRefresh"
    private const val WORK_NAME = "terrazzo.widgetRefresh.routine"

    /** How often the widgets are refreshed at most; WorkManager's floor is 15 minutes. */
    const val INTERVAL_MINUTES = 30L

    /**
     * The Home Assistants to read: those with launcher widgets on them (not the demo's, which
     * [WidgetRefreshScheduler.scheduleDemo] keeps ticking), and every signed-in one while a watch
     * has Terrazzo widgets placed.
     */
    private suspend fun servers(context: Context): Set<String> {
      val graph = context.terrazzoGraph()
      val launcher =
        graph.widgetStore.installed.first().map { it.baseUrl }.filterNot(DemoData::isDemo).toSet()
      val watch =
        if (graph.wearSyncManager.hasPlacedWidgets()) graph.mobileAppStore.instances()
        else emptyList()
      return launcher + watch
    }

    /** Schedules the refresh while there is a widget to refresh, and cancels it otherwise. */
    suspend fun sync(context: Context) {
      if (servers(context).isEmpty()) {
        cancel(context)
        return
      }
      val request =
        PeriodicWorkRequestBuilder<WidgetRoutineRefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
          .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
          )
          .build()
      // Kept as it is when already scheduled, so each app start doesn't put the next run off.
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
  }
}
