package ee.schimke.terrazzo.di

import android.content.Context
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.core.monitor.CardMonitor
import ee.schimke.terrazzo.core.wearsync.WearSyncManager

@DependencyGraph(scope = AppScope::class)
interface AppGraph : TerrazzoGraph {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides context: Context,
            @Provides cardMonitor: CardMonitor,
            @Provides wearSyncManager: WearSyncManager,
        ): AppGraph
    }
}
