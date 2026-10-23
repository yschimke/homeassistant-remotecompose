package ee.schimke.terrazzo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.createGraphFactory
import ee.schimke.ha.rc.CardDesignLibrary
import ee.schimke.ha.rc.FileLocalDesignStore
import ee.schimke.ha.rc.ShippedDesignLibrary
import ee.schimke.ha.rc.components.enableRemoteImageUrls
import ee.schimke.ha.rc.enableRemoteComposeWrapContent
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.monitor.CardMonitor
import ee.schimke.terrazzo.crash.CrashReporter
import ee.schimke.terrazzo.crash.CrashedDocuments
import ee.schimke.terrazzo.dashboard.CardDesignPreload
import ee.schimke.terrazzo.dashboard.CardDesignSync
import ee.schimke.terrazzo.dashboard.CardUsage
import ee.schimke.terrazzo.di.AppGraph
import ee.schimke.terrazzo.image.HaImageStack
import ee.schimke.terrazzo.monitor.createMonitor
import ee.schimke.terrazzo.wearsync.MobileSyncStatsStore
import ee.schimke.terrazzo.wearsync.MobileWearSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Holds the singleton [TerrazzoGraph] so every Android entry point — MainActivity, the widget
 * provider, the pin-confirmation receiver — gets the same bindings without having to re-create the
 * graph.
 */
class TerrazzoApplication : Application() {

  /**
   * Phone-only wear sync deps held outside the graph because the wear-data layer stack
   * (play-services-wearable + Horologist) only lives in this module. [syncStats] feeds [wearSync]
   * (the graph's [WearSyncManager] binding), and is also consumed directly by the sync-diagnostics
   * screen.
   */
  val syncStats: MobileSyncStatsStore by lazy { MobileSyncStatsStore(applicationContext) }
  val wearSync: MobileWearSyncManager by lazy {
    MobileWearSyncManager(applicationContext, syncStats)
  }
  val wearAppInstallHelper: ee.schimke.terrazzo.wearsync.WearAppInstallHelper by lazy {
    ee.schimke.terrazzo.wearsync.WearAppInstallHelper(
      applicationContext,
      ProcessLifecycleOwner.get().lifecycleScope,
    )
  }

  val graph: TerrazzoGraph by lazy {
    val cardMonitor = createMonitor(applicationContext)
    createGraphFactory<AppGraph.Factory>().create(applicationContext, cardMonitor, wearSync)
  }

  override fun onCreate() {
    super.onCreate()
    // Install crash logging first so anything that throws during the
    // rest of startup is still captured (the in-app LogStore sink is
    // always wired; Crashlytics rides along when a key is configured).
    installCrashLogging()
    // Flip the RemoteCompose player into wrap-content sizing —
    // see enableRemoteComposeWrapContent's KDoc. Combined with the
    // wrap-friendly profile (androidXExperimentalWrap) this lets
    // dashboard slots wrap to each card's intrinsic content height
    // instead of pinning per-card via naturalHeightDp.
    enableRemoteComposeWrapContent()
    // alpha14 gates URL/file image ops behind Limits.ENABLE_IMAGE_URLS
    // (off by default → BitmapData.read throws at parse time). The app
    // renders entity pictures / media thumbnails everywhere, so opt in.
    enableRemoteImageUrls()
    if (graph.cardMonitor.isEnabled) {
      registerNotificationChannels()
    }
    // Bind the wear sync to the application's lifecycle scope so it
    // outlives Activities (a paired watch should keep receiving
    // demo / pinned-card updates even when the phone UI is in the
    // background). PreferencesStore + WidgetStore are read from the
    // graph; the manager owns its own lazy DataClient/MessageClient.
    // Adopt the LogStore's CoroutineExceptionHandler on the wear-sync
    // scope: a stray failure in background sync gets logged instead of
    // propagating to the thread's default handler and taking the whole
    // UI process down. The `+ handler` keeps the lifecycle Job (and its
    // cancellation) intact.
    graph.wearSyncManager.start(
      scope = ProcessLifecycleOwner.get().lifecycleScope + graph.logStore.coroutineExceptionHandler,
      prefs = graph.preferencesStore,
      pinStore = graph.pinStore,
      inputEditing =
        combine(graph.preferencesStore.permanentWriteMode, graph.sessionWriteMode.writeMode) {
            permanent,
            session ->
            session ?: permanent
          }
          .stateIn(
            ProcessLifecycleOwner.get().lifecycleScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
          ),
    )
    // Designs the server lacks are captured from the app's own converters once and kept on disk
    // (the library's store), instead of on every start; stale ones from an earlier build go.
    // A template that fails to capture leaves its card on the converter path; report it as a
    // non-fatal (in-app log, and the crash backend) instead of losing it.
    CardDesignLibrary.onCaptureFailure = { _, error ->
      graph.logStore.recordCrash(error, fatal = false)
    }
    // The default style's designs ship in the app's assets: those keys are never captured.
    CardDesignLibrary.shipped = ShippedDesignLibrary(assets)
    FileLocalDesignStore.of(applicationContext).also { store ->
      CardDesignLibrary.store = store
      (ProcessLifecycleOwner.get().lifecycleScope + Dispatchers.IO).launch { store.prune() }
    }
    // Anonymous card usage: counts nothing unless the user turned sharing on.
    CardUsage.start(
      applicationContext,
      graph.preferencesStore,
      ProcessLifecycleOwner.get().lifecycleScope + graph.logStore.coroutineExceptionHandler,
    )
    // Read the cached server designs now, so the first dashboard frame already plays them.
    CardDesignPreload.start(
      applicationContext,
      graph.preferencesStore,
      ProcessLifecycleOwner.get().lifecycleScope + graph.logStore.coroutineExceptionHandler,
    )
    // Keep the card design sync scheduled for the server (cancelled when its URL is blank), and
    // sync
    // once whenever the design choice changes — on start too, which costs nothing while the cache
    // already holds every design the choice needs. Unique work, so re-applying is cheap.
    (ProcessLifecycleOwner.get().lifecycleScope + graph.logStore.coroutineExceptionHandler).launch {
      CardDesignSync.choice(applicationContext, graph.preferencesStore)
        .distinctUntilChanged()
        .collect { choice ->
          CardDesignSync.apply(applicationContext, choice.serverUrl, syncNow = true)
        }
    }
  }

  /**
   * Route uncaught exceptions to the in-app [LogStore] (persisted synchronously so the trace
   * survives the imminent process kill) and, when configured, to Crashlytics — then chain to
   * whatever handler was already installed so the platform's default crash behaviour (dialog /
   * process death) is preserved. Coroutine failures that escape a root scope without their own
   * handler also land here, since they propagate to the thread's default handler.
   */
  private fun installCrashLogging() {
    // install() registers Crashlytics' own uncaught handler (when a
    // key is configured), so we capture `previous` *after* it — our
    // handler records to the LogStore then chains through, letting
    // Crashlytics report the fatal crash natively. We deliberately
    // don't also call crashReporter.recordCrash here: that path is
    // for non-fatal reports and would double-count an uncaught crash.
    crashReporter.install(applicationContext)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      CrashedDocuments.recordCrash(applicationContext)
      runCatching { graph.logStore.recordCrash(throwable, thread, fatal = true) }
      previous?.uncaughtException(thread, throwable)
    }
    // Forward caught/non-fatal crashes (e.g. coroutine failures routed
    // through the LogStore's CoroutineExceptionHandler) to the backend
    // as non-fatals. Fatal crashes aren't routed here — they're already
    // reported by the backend's own uncaught handler via the chain above.
    // This is the first `graph` access, which triggers graph init; we do
    // it *after* installing the handler so a failure during init is still
    // captured rather than escaping uncovered.
    graph.logStore.nonFatalSink = crashReporter::recordCrash
    // A document a player gave up on is reported as a non-fatal; one that crashed the last run is
    // disabled from the start.
    CrashedDocuments.install(applicationContext, crashReporter::recordCrash)
  }

  /**
   * Optional external crash sink: the backend selected at build time (`harc.crashReporter`, see
   * [CrashReporter.create]). With none selected it's a no-op and the app runs crash-reporting-free
   * off the [LogStore] sink alone.
   */
  private val crashReporter: CrashReporter by lazy { CrashReporter.create() }

  private fun registerNotificationChannels() {
    val nm = getSystemService(NotificationManager::class.java) ?: return
    val channel =
      NotificationChannel(
          CardMonitor.CHANNEL_ID,
          "Card monitoring",
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply {
          description = "Shown while a card is being monitored in a bounded window."
          setShowBadge(false)
        }
    nm.createNotificationChannel(channel)
  }
}

/**
 * Convenience accessor for non-Compose entry points (BroadcastReceiver, AppWidgetProvider) where we
 * only have a [Context].
 */
fun Context.terrazzoGraph(): TerrazzoGraph = (applicationContext as TerrazzoApplication).graph

/**
 * CompositionLocal surfaced so Compose screens can pull bindings without walking the Application
 * cast. Wired up in [MainActivity].
 */
val LocalTerrazzoGraph =
  staticCompositionLocalOf<TerrazzoGraph> {
    error("TerrazzoGraph not provided; wrap your content in LocalTerrazzoGraph.provides(...)")
  }

/**
 * Process-singleton image stack (one [coil3.ImageLoader] + [coil3.disk.DiskCache] for the entire
 * app). Provided alongside [LocalTerrazzoGraph] in [MainActivity] for real surfaces; previews and
 * unit tests leave it `null`, in which case dashboard screens fall back to the player's per-call
 * `BitmapLoader` path with no live picture-entity refresh.
 */
val LocalHaImageStack = staticCompositionLocalOf<HaImageStack?> { null }
