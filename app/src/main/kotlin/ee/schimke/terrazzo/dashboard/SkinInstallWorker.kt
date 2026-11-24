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
import ee.schimke.ha.design.cardFingerprint
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import kotlinx.coroutines.flow.first

/** Downloads a skin and applies it to its card, even after the picker has closed. */
class SkinInstallWorker
@dev.zacsweers.metro.AssistedInject
constructor(
  @dev.zacsweers.metro.Assisted context: Context,
  @dev.zacsweers.metro.Assisted params: WorkerParameters,
  private val prefs: ee.schimke.terrazzo.core.prefs.PreferencesStore,
  private val overrideStore: CardDesignOverrideStore,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val designId = inputData.getString(DESIGN) ?: return Result.failure()
    val cardKey = inputData.getString(CARD) ?: return Result.failure()
    val choice = CardDesignSync.choice(applicationContext, prefs).first()
    val installed =
      installAndSelect(
        install = { Skins.install(applicationContext, choice, designId) == Skins.Installed.Yes },
        select = { overrideStore.setDesign(cardKey, designId) },
        refresh = { WidgetRefreshScheduler(applicationContext).refreshAllNow() },
      )
    return if (installed) Result.success() else Result.failure()
  }

  @dev.zacsweers.metro.AssistedFactory
  @dev.zacsweers.metro.ContributesIntoMap(
    ee.schimke.terrazzo.core.di.AppScope::class,
    dev.zacsweers.metro.binding<ee.schimke.terrazzo.di.AssistedWorkerFactory>(),
  )
  @ee.schimke.terrazzo.di.WorkerKey(SkinInstallWorker::class)
  fun interface Factory : ee.schimke.terrazzo.di.AssistedWorkerFactory {
    override fun create(context: Context, params: WorkerParameters): SkinInstallWorker
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
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
          )
          .addTag(TAG)
          .addTag(installTag(card, designId))
          .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(installTag(card, designId), ExistingWorkPolicy.KEEP, request)
    }
  }
}
