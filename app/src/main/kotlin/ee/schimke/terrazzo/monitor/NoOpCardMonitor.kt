package ee.schimke.terrazzo.monitor

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.core.monitor.CardMonitor

@Inject
class NoOpCardMonitor : CardMonitor {
    override val isEnabled: Boolean = false
    override fun start(card: CardConfig, durationMinutes: Int) {
        // No-op
    }
}
