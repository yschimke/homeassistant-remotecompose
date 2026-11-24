package ee.schimke.terrazzo.monitor

import android.content.Context
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.monitor.CardMonitor

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoOpCardMonitor::class])
@Inject
class RealCardMonitor(private val context: Context) : CardMonitor {
  override val isEnabled: Boolean = true

  override fun start(card: CardConfig, durationMinutes: Int) {
    MonitoringService.start(context, card, durationMinutes)
  }
}
