package ee.schimke.terrazzo.monitor

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.monitor.CardMonitor

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NoOpCardMonitor : CardMonitor {
  override val isEnabled: Boolean = false

  override fun start(card: CardConfig, durationMinutes: Int) {
    // No-op
  }
}
