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
import ee.schimke.ha.client.design.cardFingerprint
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.terrazzoGraph
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import kotlinx.coroutines.flow.first

/** Downloads a skin and applies it to its card, even after the picker has closed. */
class SkinInstallWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val designId = inputData.getString(DESIGN) ?: return Result.failure()
    val cardKey = inputData.getString(CARD) ?: return Result.failure()
    val prefs = applicationContext.terrazzoGraph().preferencesStore
    val choice = CardDesignSync.choice(applicationContext, prefs).first()
    val installed =
      installAndSelect(
        install = { Skins.install(applicationContext, choice, designId) == Skins.Installed.Yes },
        select = { CardDesignOverrideStore.of(applicationContext).setDesign(cardKey, designId) },
        refresh = { WidgetRefreshScheduler(applicationContext).refreshAllNow() },
      )
    return if (installed) Result.success() else Result.failure()
  }

  companion object {
    const val TAG = "terrazzo.skin.install"
    private const val DESIGN = "design"
    private const val CARD = "card"

    fun installTag(card: CardConfig, designId: String): String =
      "$TAG.${cardFingerprint(card)}.$designId"

    fun enqueue(context: Context, card: CardConfig, designId: String) {
      val request =
        OneTimeWorkRequestBuilder<SkinInstallWorker>()
          .setInputData(workDataOf(DESIGN to designId, CARD to cardFingerprint(card)))
          .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
          )
          .addTag(TAG)
          .addTag(installTag(card, designId))
          .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(installTag(card, designId), ExistingWorkPolicy.KEEP, request)
    }
  }
}
