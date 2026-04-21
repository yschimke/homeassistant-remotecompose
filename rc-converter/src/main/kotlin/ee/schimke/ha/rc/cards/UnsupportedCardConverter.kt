package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.UNSUPPORTED_CARD_TYPE
import ee.schimke.ha.rc.components.HaUnsupportedData
import ee.schimke.ha.rc.components.RemoteHaUnsupported

/**
 * Fallback for card types we haven't implemented. Renders a visible
 * placeholder so the dashboard still lays out correctly and missing
 * pieces are obvious.
 */
class UnsupportedCardConverter(
    private val declaredType: String? = null,
) : CardConverter {
    override val cardType: String = declaredType ?: UNSUPPORTED_CARD_TYPE

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        RemoteHaUnsupported(HaUnsupportedData(cardType = card.type.rs))
    }
}
