package ee.schimke.terrazzo.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.terrazzoGraph
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking

/**
 * Keeps the app connected while a widget's entity moves (`closing` → `closed`, `unlocking` →
 * `unlocked`, …) or after a state wake from the Terrazzo push relay
 * (`docs/architecture/live-widget-updates.md`).
 *
 * Expedited, with a network constraint so a Wi-Fi-to-cellular handoff waits for the new link rather
 * than burning its wake offline. With the app's own session connected, its follower is drawing
 * already ([LiveWidgetUpdates.follow]) and this only redraws once. Otherwise it connects with the
 * stored login and follows the states (reconnecting if the socket drops mid-movement), holding the
 * integration's lease when registered, while any widget entity is moving and for [SETTLE_MS] after
 * the last visible change, up to [MAX_WINDOW_MS]. If the connection fails or the window runs out
 * while an entity is still moving, WorkManager retries up to [MAX_ATTEMPTS] times so the widget
 * doesn't stay stuck on `closing`.
 */
class WidgetStateWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val graph = applicationContext.terrazzoGraph()
    val webhookFromInput = inputData.getString(KEY_WEBHOOK_ID)
    val baseUrl =
      inputData.getString(KEY_BASE_URL)
        ?: webhookFromInput?.let { graph.mobileAppStore.instanceForWebhook(it) }
        ?: return Result.success()
    if (DemoData.isDemo(baseUrl)) return Result.success()
    val live = WidgetActionRunner.connectedAppSession(baseUrl)
    if (live != null) {
      runCatching {
        val snapshot = live.liveStates.value ?: live.loadSnapshot()
        LiveWidgetUpdates.redraw(applicationContext, baseUrl, snapshot)
      }
      return Result.success()
    }
    if (WidgetActionRunner.newSessionFactory == null && graph.tokenVault.get(baseUrl) == null) {
      return Result.success()
    }
    val webhookId = webhookFromInput ?: graph.mobileAppStore.get(baseUrl)?.webhookId
    val started = clock()
    var receivedSnapshot = false
    val outcome = runCatching {
      WidgetActionRunner.withSession(applicationContext, baseUrl, maxWindowMs + connectMs) { session
        ->
        LiveWidgetUpdates.follow(
          context = applicationContext,
          session = session,
          webhookId = webhookId,
          reconnect = true,
        ) { watched, sinceChangeMs ->
          if (watched != null) receivedSnapshot = true
          val moving = watched?.values?.any(WidgetEdges::isMoving) == true
          clock() - started >= maxWindowMs ||
            (watched != null && !moving && sinceChangeMs >= settleMs)
        }
      }
    }
    if (outcome.isFailure) {
      val failure = outcome.exceptionOrNull()
      if (failure is CancellationException && failure !is TimeoutCancellationException) {
        throw failure
      }
      Log.w(
        TAG,
        "Couldn't follow $baseUrl after a widget wake (attempt $runAttemptCount)",
        failure,
      )
      return retryOrGiveUp(baseUrl)
    }
    if (!receivedSnapshot || LiveWidgetUpdates.hasMovingEntity(applicationContext, baseUrl)) {
      Log.i(TAG, "Widget entity on $baseUrl hasn't settled after window; retrying")
      return retryOrGiveUp(baseUrl)
    }
    gaveUpAt.remove(baseUrl)
    return Result.success()
  }

  private fun retryOrGiveUp(baseUrl: String): Result =
    if (runAttemptCount < MAX_ATTEMPTS) {
      Result.retry()
    } else {
      gaveUpAt[baseUrl] = clock()
      Result.success()
    }

  companion object {
    private const val TAG = "WidgetStateWorker"
    internal const val KEY_WEBHOOK_ID = "terrazzo.webhookId"
    internal const val KEY_BASE_URL = "terrazzo.baseUrl"

    /** How long a window stays after the last visible change, for one that follows soon after. */
    const val SETTLE_MS = 20_000L

    /** The longest window: an expedited job is meant to finish within minutes. */
    const val MAX_WINDOW_MS = 120_000L

    private const val CONNECT_MS = 20_000L

    /** Bounded retries when offline or still moving at the end of a window. */
    const val MAX_ATTEMPTS = 4

    /** Cooldown after exhausting [MAX_ATTEMPTS] on a stuck entity so renders don't re-loop. */
    const val GIVE_UP_COOLDOWN_MS = 15 * 60_000L

    internal var settleMs: Long = SETTLE_MS
    internal var maxWindowMs: Long = MAX_WINDOW_MS
    internal var connectMs: Long = CONNECT_MS
    internal var giveUpCooldownMs: Long = GIVE_UP_COOLDOWN_MS
    internal var clock: () -> Long = System::currentTimeMillis
    private val gaveUpAt = ConcurrentHashMap<String, Long>()

    internal fun resetForTest() {
      settleMs = SETTLE_MS
      maxWindowMs = MAX_WINDOW_MS
      connectMs = CONNECT_MS
      giveUpCooldownMs = GIVE_UP_COOLDOWN_MS
      clock = System::currentTimeMillis
      gaveUpAt.clear()
    }

    internal fun workName(target: String): String = "terrazzo.widgetState.$target"

    private fun buildRequest(data: Data) =
      OneTimeWorkRequestBuilder<WidgetStateWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInputData(data)
        .build()

    /**
     * Runs a window for [webhookId] from an FCM state wake. A wake only comes while no window holds
     * the lease, so one still running is ending: the new one replaces it.
     */
    fun enqueue(context: Context, webhookId: String) {
      val baseUrl = runBlocking {
        runCatching { context.terrazzoGraph().mobileAppStore.instanceForWebhook(webhookId) }
          .getOrNull()
      }
      if (baseUrl != null) gaveUpAt.remove(baseUrl)
      val data =
        if (baseUrl != null) workDataOf(KEY_BASE_URL to baseUrl, KEY_WEBHOOK_ID to webhookId)
        else workDataOf(KEY_WEBHOOK_ID to webhookId)
      WorkManager.getInstance(context)
        .enqueueUniqueWork(
          workName(baseUrl ?: webhookId),
          ExistingWorkPolicy.REPLACE,
          buildRequest(data),
        )
    }

    /**
     * Ensures a background follow window is running for [baseUrl] when a widget tap, foreground
     * exit, or widget render detects a temporary moving state (`closing`, `opening`, …). Keeps an
     * already-running window untouched (`KEEP`).
     */
    fun ensureFollowing(context: Context, baseUrl: String, resetCooldown: Boolean = false) {
      if (baseUrl.isBlank() || DemoData.isDemo(baseUrl)) return
      if (resetCooldown) {
        gaveUpAt.remove(baseUrl)
      } else if (gaveUpAt[baseUrl]?.let { clock() - it < giveUpCooldownMs } == true) {
        return
      }
      WorkManager.getInstance(context)
        .enqueueUniqueWork(
          workName(baseUrl),
          ExistingWorkPolicy.KEEP,
          buildRequest(workDataOf(KEY_BASE_URL to baseUrl)),
        )
    }
  }
}
