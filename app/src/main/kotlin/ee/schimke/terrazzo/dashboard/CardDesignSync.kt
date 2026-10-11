package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ee.schimke.ha.client.design.DesignRepository
import ee.schimke.ha.client.design.DesignServerConfig
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.cachedDesignRepository
import ee.schimke.ha.client.design.createDesignRepository
import ee.schimke.terrazzo.terrazzoGraph
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background sync for the experimental card design server. Designs change rarely, so the network
 * work is a nightly job that only runs while the device is charging and on an unmetered network;
 * the dashboard itself never fetches, it only reads what this job left on disk.
 */
object CardDesignSync {

  /** Serializes refreshes across worker instances (WorkManager runs workers in-process). */
  internal val refreshLock = Mutex()

  private const val PERIODIC_WORK = "terrazzo.cardDesigns.nightly"
  private const val NOW_WORK = "terrazzo.cardDesigns.now"

  /**
   * Match the scheduled work to [serverUrl]: blank cancels everything (the feature is off);
   * otherwise keep the nightly job scheduled and, when [syncNow], fetch once as soon as there is a
   * network so a newly entered URL doesn't wait a day to take effect.
   */
  fun apply(context: Context, serverUrl: String, syncNow: Boolean = false) {
    val workManager = WorkManager.getInstance(context)
    if (serverUrl.isBlank()) {
      workManager.cancelUniqueWork(PERIODIC_WORK)
      workManager.cancelUniqueWork(NOW_WORK)
      return
    }
    val nightly =
      PeriodicWorkRequestBuilder<CardDesignSyncWorker>(1, TimeUnit.DAYS)
        .setConstraints(
          Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        )
        .build()
    workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, nightly)
    if (syncNow) {
      val now =
        OneTimeWorkRequestBuilder<CardDesignSyncWorker>()
          .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
          )
          .build()
      workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, now)
    }
  }

  /** Cache directory for [serverUrl]: one per server, so switching URLs never mixes manifests. */
  fun storeDir(context: Context, serverUrl: String): File =
    File(
      context.filesDir,
      "terrazzo/designs/${Sha256.hex(serverUrl.trimEnd('/').encodeToByteArray())}",
    )

  /** Repository that can refresh from [serverUrl]. Only the sync worker uses this. */
  internal fun networkRepository(context: Context, serverUrl: String): DesignRepository =
    createDesignRepository(
      config = DesignServerConfig(baseUrl = serverUrl, allowUnsigned = true),
      store = FileDesignStore(storeDir(context, serverUrl)),
      clock = System::currentTimeMillis,
    )

  /** Read-only repository over the on-disk cache for [serverUrl]: no HTTP client at all. */
  fun cachedRepository(context: Context, serverUrl: String): DesignRepository =
    cachedDesignRepository(
      store = FileDesignStore(storeDir(context, serverUrl)),
      clock = System::currentTimeMillis,
    )
}

/** Refreshes the card design cache for the configured server, if one is configured. */
class CardDesignSyncWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val url = applicationContext.terrazzoGraph().preferencesStore.designServerUrl.first()
    if (url.isBlank()) return Result.success()
    // `refresh` already respects the manifest TTL and never throws for network trouble; a failed
    // run just waits for the next scheduled one.
    // The nightly and the on-change job can be due at the same moment; one refresh at a time per
    // process keeps them from writing the same cache files concurrently.
    val result =
      CardDesignSync.refreshLock.withLock {
        CardDesignSync.networkRepository(applicationContext, url).refresh()
      }
    Log.d(TAG, "card design sync: $result")
    return Result.success()
  }

  private companion object {
    const val TAG = "CardDesignSync"
  }
}
