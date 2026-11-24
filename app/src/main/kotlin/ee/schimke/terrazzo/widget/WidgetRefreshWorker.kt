package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.di.AssistedWorkerFactory
import ee.schimke.terrazzo.di.WorkerKey
import kotlinx.coroutines.flow.first

/**
 * Broadcasts `ACTION_APPWIDGET_UPDATE` to every pinned Terrazzo widget so the provider re-renders.
 * Used today to animate demo-mode widgets; the same worker will host the live-mode state-fetch +
 * snapshot-cache path once that lands (TODO).
 *
 * Self-chaining when enqueued via [WidgetRefreshScheduler.scheduleDemo]: the worker re-enqueues
 * itself on completion as long as demo mode is still on. Chained `OneTimeWorkRequest`s (vs.
 * `PeriodicWorkRequest`) keep the cadence adjustable at runtime.
 */
class WidgetRefreshWorker
@AssistedInject
constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val widgetStore: WidgetStore,
  private val preferencesStore: PreferencesStore,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    // Refresh only widgets the launcher still hosts; prunes rows for ones removed behind our back.
    WidgetSync.reconcile(applicationContext)
    val ids = widgetStore.installed.first().map { it.widgetId }.toIntArray()

    if (ids.isNotEmpty()) {
      val intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
          component = ComponentName(applicationContext, TerrazzoWidgetProvider::class.java)
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
      applicationContext.sendBroadcast(intent)
    } else {
      Log.d(TAG, "no pinned widgets; skipping broadcast")
    }

    if (inputData.getBoolean(KEY_CHAIN_DEMO, false) && preferencesStore.demoModeNow()) {
      val delay = inputData.getLong(KEY_CHAIN_DELAY_MINUTES, DEFAULT_DEMO_INTERVAL_MINUTES)
      WidgetRefreshScheduler(applicationContext).scheduleDemo(delayMinutes = delay)
    }

    return Result.success()
  }

  @AssistedFactory
  @ContributesIntoMap(AppScope::class, binding<AssistedWorkerFactory>())
  @WorkerKey(WidgetRefreshWorker::class)
  fun interface Factory : AssistedWorkerFactory {
    override fun create(context: Context, params: WorkerParameters): WidgetRefreshWorker
  }

  companion object {
    const val KEY_CHAIN_DEMO = "terrazzo.chainDemo"
    const val KEY_CHAIN_DELAY_MINUTES = "terrazzo.chainDelayMinutes"
    const val DEFAULT_DEMO_INTERVAL_MINUTES = 10L
    private const val TAG = "WidgetRefreshWorker"
  }
}
