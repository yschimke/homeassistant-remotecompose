package ee.schimke.terrazzo.di

import android.content.Context
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.di.TerrazzoGraph
import ee.schimke.terrazzo.crash.CrashReporter
import ee.schimke.terrazzo.dashboard.CardDesignOverrideStore
import ee.schimke.terrazzo.dashboard.CardStyleStore
import ee.schimke.terrazzo.dashboard.CardUsage
import ee.schimke.terrazzo.dashboard.PlaceholderCardStore
import ee.schimke.terrazzo.dashboard.SkinStore
import ee.schimke.terrazzo.image.HaImageStack
import ee.schimke.terrazzo.wearsync.MobileSyncStatsStore
import ee.schimke.terrazzo.wearsync.WearAppInstallHelper

interface AppGraph : TerrazzoGraph, MetroAppComponentProviders, ViewModelGraph {
  /**
   * Process-singleton Coil [coil3.ImageLoader] + [coil3.disk.DiskCache] for HA-aware image fetches.
   * Coil documents that exactly one `DiskCache` instance per directory is safe — without a shared
   * graph binding, every screen that builds its own loader points a fresh `DiskCache` at the same
   * path and the journals can interleave.
   */
  val haImageStack: HaImageStack

  val crashReporter: CrashReporter
  val syncStatsStore: MobileSyncStatsStore
  val wearAppInstallHelper: WearAppInstallHelper
  val skinStore: SkinStore
  val placeholderCardStore: PlaceholderCardStore
  val cardStyleStore: CardStyleStore
  val cardDesignOverrideStore: CardDesignOverrideStore
  val cardUsage: CardUsage
  val workerFactory: MetroWorkerFactory
}

@DependencyGraph(scope = AppScope::class)
interface RealAppGraph : AppGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides context: Context): RealAppGraph
  }
}
