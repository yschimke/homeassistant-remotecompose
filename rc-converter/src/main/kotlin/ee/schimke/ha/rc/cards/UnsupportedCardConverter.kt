package ee.schimke.ha.rc.cards

import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.UNSUPPORTED_CARD_TYPE
import androidx.compose.remote.creation.compose.state.rs
import ee.schimke.ha.rc.components.RemoteHaUnsupported

/**
 * Fallback for card types we haven't implemented (complex graphs, media,
 * picture-elements, etc). Renders a visible placeholder so the dashboard
 * still lays out correctly and missing pieces are obvious.
 *
 * The registry routes unknown types here via
 * [ee.schimke.ha.rc.RenderChild] — this converter registers under a
 * sentinel type and the renderer consults it when the requested type
 * has no dedicated converter.
 */
class UnsupportedCardConverter(
    private val declaredType: String? = null,
) : CardConverter {
    override val cardType: String = declaredType ?: UNSUPPORTED_CARD_TYPE

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        RemoteHaUnsupported(cardType = card.type.rs)
    }
}
