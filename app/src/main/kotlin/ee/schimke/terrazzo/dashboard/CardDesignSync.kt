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
import ee.schimke.ha.client.design.RefreshResult
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.cachedDesignRepository
import ee.schimke.ha.client.design.createDesignRepository
import ee.schimke.ha.design.DesignTags
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.terrazzoGraph
import ee.schimke.terrazzo.ui.toThemeStyle
import ee.schimke.terrazzo.ui.toUberTheme
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background sync for the card design server. Designs change rarely, so the manifest is refreshed
 * by a nightly job that only runs while the device is charging and on an unmetered network; a
 * one-off sync runs whenever the [CardDesignChoice] changes, which downloads only the designs the
 * new choice needs. The dashboard itself never fetches: it reads what these jobs left on disk.
 */
object CardDesignSync {

  private val _revision = MutableStateFlow(0)

  /** Bumped whenever a sync stores designs, so a dashboard reads the cache again. */
  val revision: StateFlow<Int> = _revision.asStateFlow()

  internal fun stored() {
    _revision.update { it + 1 }
  }

  /** The user's [CardDesignChoice], from their settings and their card design overrides. */
  fun choice(context: Context, prefs: PreferencesStore): Flow<CardDesignChoice> =
    combine(
        prefs.designServerUrl,
        prefs.themeStyle,
        prefs.cardStyle,
        prefs.designVariant,
        prefs.designAuthor,
      ) { url, theme, cardStyle, variant, author ->
        CardDesignChoice(url.trim(), theme.toThemeStyle(), cardStyle.toUberTheme(), variant, author)
      }
      .combine(CardDesignOverrideStore.of(context).overrides) { choice, overrides ->
        choice.copy(overrides = overrides)
      }
      .combine(prefs.designToken) { choice, token -> choice.copy(token = token) }
      .combine(prefs.catalogCardStyle) { choice, style -> choice.copy(catalogStyleId = style) }

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

  /** Repository that refreshes the designs [choice] keeps. Only the sync worker uses this. */
  internal fun networkRepository(context: Context, choice: CardDesignChoice): DesignRepository =
    createDesignRepository(
      config =
        DesignServerConfig(
          baseUrl = choice.serverUrl,
          allowUnsigned = true,
          choice = choice.queries,
          token = choice.token,
        ),
      store = FileDesignStore(storeDir(context, choice.serverUrl)),
      clock = System::currentTimeMillis,
    )

  /**
   * Every tag value the cached manifest for [serverUrl] offers, with counts: what the settings
   * pickers list. Null before a sync has stored a manifest.
   */
  suspend fun tags(context: Context, serverUrl: String): DesignTags? =
    if (serverUrl.isBlank()) null
    else
      FileDesignStore(storeDir(context, serverUrl)).readManifest()?.manifest?.designs?.let {
        DesignTags.of(it)
      }

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
    val choice =
      CardDesignSync.choice(applicationContext, applicationContext.terrazzoGraph().preferencesStore)
        .first()
    if (!choice.enabled) return Result.success()
    // `refresh` already respects the manifest TTL — within it, it only fetches designs the choice
    // needs and the cache lacks — and never throws for network trouble; a failed run just waits for
    // the next scheduled one.
    // The nightly and the on-change job can be due at the same moment; one refresh at a time per
    // process keeps them from writing the same cache files concurrently.
    val result =
      CardDesignSync.refreshLock.withLock {
        CardDesignSync.networkRepository(applicationContext, choice).refresh()
      }
    if (result == RefreshResult.Updated || result == RefreshResult.Filled) CardDesignSync.stored()
    Log.d(TAG, "card design sync: $result")
    return Result.success()
  }

  private companion object {
    const val TAG = "CardDesignSync"
  }
}
