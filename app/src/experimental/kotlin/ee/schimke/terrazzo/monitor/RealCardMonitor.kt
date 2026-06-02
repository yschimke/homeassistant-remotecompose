package ee.schimke.terrazzo.monitor

import android.content.Context
import dev.zacsweers.metro.Inject
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.core.monitor.CardMonitor

@Inject
class RealCardMonitor(private val context: Context) : CardMonitor {
  override val isEnabled: Boolean = true

  override fun start(card: CardConfig, durationMinutes: Int) {
    MonitoringService.start(context, card, durationMinutes)
  }
}
