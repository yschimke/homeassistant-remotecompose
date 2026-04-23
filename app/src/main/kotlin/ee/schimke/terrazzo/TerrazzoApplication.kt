package ee.schimke.terrazzo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import dev.zacsweers.metro.createGraphFactory
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.monitor.MonitoringService

/**
 * Holds the singleton [TerrazzoGraph] so every Android entry point —
 * MainActivity, the widget provider, the pin-confirmation receiver —
 * gets the same bindings without having to re-create the graph.
 */
class TerrazzoApplication : Application() {

    val graph: TerrazzoGraph by lazy {
        createGraphFactory<TerrazzoGraph.Factory>().create(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
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
