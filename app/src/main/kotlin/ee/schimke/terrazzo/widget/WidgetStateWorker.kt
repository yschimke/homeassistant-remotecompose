package ee.schimke.terrazzo.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ee.schimke.terrazzo.terrazzoGraph

/**
 * A state wake from the Terrazzo push relay: an entity a widget reads visibly changed on the Home
 * Assistant that registration [KEY_WEBHOOK_ID] belongs to
 * (`docs/architecture/live-widget-updates.md`).
 *
 * Expedited, as a high-priority FCM message allows. With the app's own session connected, its
 * follower is drawing already ([LiveWidgetUpdates.follow]) and this only redraws once. Otherwise it
 * connects with the stored login and follows the states, holding the integration's lease, while a
 * widget's entity is moving (a garage door `opening`) and for [SETTLE_MS] after the last change,
 * and for [MAX_WINDOW_MS] at most; then the integration's wakes take over again.
 */
class WidgetStateWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val webhookId = inputData.getString(KEY_WEBHOOK_ID) ?: return Result.success()
    val baseUrl =
      applicationContext.terrazzoGraph().mobileAppStore.instanceForWebhook(webhookId)
        ?: return Result.success()
    runCatching {
      val live = WidgetActionRunner.connectedAppSession(baseUrl)
      if (live != null) {
        val snapshot = live.liveStates.value ?: live.loadSnapshot()
        LiveWidgetUpdates.redraw(applicationContext, baseUrl, snapshot)
        return@runCatching
      }
      val started = System.currentTimeMillis()
      WidgetActionRunner.withSession(applicationContext, baseUrl, MAX_WINDOW_MS + CONNECT_MS) {
        session ->
        LiveWidgetUpdates.follow(applicationContext, session, webhookId) { watched, sinceChangeMs ->
          val moving = watched.values.any(WidgetEdges::isMoving)
          System.currentTimeMillis() - started >= MAX_WINDOW_MS ||
            (!moving && sinceChangeMs >= SETTLE_MS)
        }
      }
    }
      .onFailure { Log.w(TAG, "Couldn't follow $baseUrl after a state wake", it) }
    return Result.success()
  }

  companion object {
    private const val TAG = "WidgetStateWorker"
    private const val KEY_WEBHOOK_ID = "terrazzo.webhookId"

    /** How long a window stays after the last change, for one that follows soon after. */
    const val SETTLE_MS = 20_000L

    /** The longest window: an expedited job is meant to finish within minutes. */
    const val MAX_WINDOW_MS = 120_000L

    private const val CONNECT_MS = 20_000L

    /**
     * Runs a window for [webhookId]. A wake only comes while no window holds the lease, so one
     * still running is ending: the new one replaces it.
     */
    fun enqueue(context: Context, webhookId: String) {
      val request =
        OneTimeWorkRequestBuilder<WidgetStateWorker>()
          .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
          .setInputData(workDataOf(KEY_WEBHOOK_ID to webhookId))
          .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork("terrazzo.widgetState.$webhookId", ExistingWorkPolicy.REPLACE, request)
    }
  }
}
