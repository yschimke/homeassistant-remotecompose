package ee.schimke.terrazzo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.createGraphFactory
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.monitor.MonitoringService
import ee.schimke.terrazzo.wearsync.MobileSyncStatsStore
import ee.schimke.terrazzo.wearsync.MobileWearSyncManager

/**
 * Holds the singleton [TerrazzoGraph] so every Android entry point —
 * MainActivity, the widget provider, the pin-confirmation receiver —
 * gets the same bindings without having to re-create the graph.
 */
class TerrazzoApplication : Application() {

    val graph: TerrazzoGraph by lazy {
        createGraphFactory<TerrazzoGraph.Factory>().create(applicationContext)
    }

    /**
     * Phone-only wear sync. Held outside the Metro graph because the
     * wear-data layer dependency stack (play-services-wearable +
     * Horologist) only lives in this module. Started on first access.
     */
    val syncStats: MobileSyncStatsStore by lazy { MobileSyncStatsStore(applicationContext) }
    val wearSync: MobileWearSyncManager by lazy {
        MobileWearSyncManager(applicationContext, syncStats)
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
        // Bind the wear sync to the application's lifecycle scope so it
        // outlives Activities (a paired watch should keep receiving
        // demo / pinned-card updates even when the phone UI is in the
        // background). PreferencesStore + WidgetStore are read from the
        // graph; the manager owns its own lazy DataClient/MessageClient.
        wearSync.start(
            scope = ProcessLifecycleOwner.get().lifecycleScope,
            prefs = graph.preferencesStore,
            widgetStore = graph.widgetStore,
        )
    }

    private fun registerNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            MonitoringService.CHANNEL_ID,
            "Card monitoring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a card is being monitored in a bounded window."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}

/**
 * Convenience accessor for non-Compose entry points (BroadcastReceiver,
 * AppWidgetProvider) where we only have a [Context].
 */
fun Context.terrazzoGraph(): TerrazzoGraph =
    (applicationContext as TerrazzoApplication).graph

/**
 * CompositionLocal surfaced so Compose screens can pull bindings
 * without walking the Application cast. Wired up in [MainActivity].
 */
val LocalTerrazzoGraph = staticCompositionLocalOf<TerrazzoGraph> {
    error("TerrazzoGraph not provided; wrap your content in LocalTerrazzoGraph.provides(...)")
}
