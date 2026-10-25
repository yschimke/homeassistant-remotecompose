package ee.schimke.terrazzo.dashboard

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.terrazzoGraph
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import kotlinx.coroutines.flow.first

/** Installs independently of Settings, selecting the style only once all its assets are ready. */
class CardStyleInstallWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val style =
      CardStylePref.entries.firstOrNull { it.id == inputData.getString(STYLE) }
        ?: return Result.failure()
    val prefs = applicationContext.terrazzoGraph().preferencesStore
    val choice = CardDesignSync.choice(applicationContext, prefs).first()
    val installed =
      installAndSelect(
        install = {
          CardStyles.install(applicationContext, choice, style.id) == CardStyles.Installed.Yes
        },
        select = { prefs.setCardStyle(style) },
        refresh = { WidgetRefreshScheduler(applicationContext).refreshAllNow() },
      )
    return if (installed) Result.success() else Result.failure()
  }

  companion object {
    const val TAG = "terrazzo.cardStyle.install"
    private const val STYLE = "style"

    fun styleTag(styleId: String): String = "$TAG.$styleId"

    fun enqueue(context: Context, style: CardStylePref) {
      val request =
        OneTimeWorkRequestBuilder<CardStyleInstallWorker>()
          .setInputData(workDataOf(STYLE to style.id))
          .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
          )
          .addTag(TAG)
          .addTag(styleTag(style.id))
          .build()
      // Double taps keep the active download. A failed install can be explicitly retried.
      WorkManager.getInstance(context)
        .enqueueUniqueWork(styleTag(style.id), ExistingWorkPolicy.KEEP, request)
    }
  }
}

/** Shared completion order for background style and skin installs. Failures keep the selection. */
internal suspend fun installAndSelect(
  install: suspend () -> Boolean,
  select: suspend () -> Unit,
  refresh: suspend () -> Unit,
): Boolean {
  if (!install()) return false
  select()
  refresh()
  return true
}
