package ee.schimke.terrazzo.core.monitor

import ee.schimke.ha.model.CardConfig

/**
 * User-initiated bounded monitoring window for a single card.
 */
interface CardMonitor {
    val isEnabled: Boolean

    fun start(card: CardConfig, durationMinutes: Int = 15)

    companion object {
        const val CHANNEL_ID = "terrazzo.monitoring"
    }
}
