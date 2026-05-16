package ee.schimke.terrazzo.di

import android.content.Context
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.monitor.CardMonitor
import ee.schimke.terrazzo.core.wearsync.WearSyncManager
import ee.schimke.terrazzo.image.HaImageStack

@DependencyGraph(scope = AppScope::class)
interface AppGraph : TerrazzoGraph {
    /**
     * Process-singleton Coil [coil3.ImageLoader] + [coil3.disk.DiskCache]
     * for HA-aware image fetches. Coil documents that exactly one
     * `DiskCache` instance per directory is safe — without a shared
     * graph binding, every screen that builds its own loader points a
     * fresh `DiskCache` at the same path and the journals can
     * interleave.
     */
    val haImageStack: HaImageStack

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides context: Context,
            @Provides cardMonitor: CardMonitor,
            @Provides wearSyncManager: WearSyncManager,
        ): AppGraph
    }
}
