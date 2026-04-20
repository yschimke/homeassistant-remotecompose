package ee.schimke.ha.rc.cards

import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.components.RemoteHaGrid
import ee.schimke.ha.rc.components.RemoteHaHorizontalStack
import ee.schimke.ha.rc.components.RemoteHaVerticalStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Layout-only cards. Each unrolls its `cards:` array through
 * `RenderChild`, which dispatches to the registered converter per child.
 */

class VerticalStackCardConverter : CardConverter {
    override val cardType: String = CardTypes.VERTICAL_STACK

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        RemoteHaVerticalStack {
            childCards(card).forEach { RenderChild(it, snapshot) }
        }
    }
}

class HorizontalStackCardConverter : CardConverter {
    override val cardType: String = CardTypes.HORIZONTAL_STACK

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        RemoteHaHorizontalStack {
            childCards(card).forEach { RenderChild(it, snapshot) }
        }
    }
}

class GridCardConverter : CardConverter {
    override val cardType: String = CardTypes.GRID

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        RemoteHaGrid {
            childCards(card).forEach { RenderChild(it, snapshot) }
        }
    }
}

private fun childCards(card: CardConfig): List<CardConfig> =
    card.raw["cards"]?.jsonArray
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { obj ->
            val type = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            CardConfig(type = type, raw = obj)
        }
        ?: emptyList()
