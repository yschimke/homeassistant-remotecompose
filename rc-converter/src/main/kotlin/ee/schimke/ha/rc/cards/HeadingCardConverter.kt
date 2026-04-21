package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaHeadingData
import ee.schimke.ha.rc.components.HaHeadingStyle
import ee.schimke.ha.rc.components.RemoteHaHeading
import kotlinx.serialization.json.jsonPrimitive

class HeadingCardConverter : CardConverter {
    override val cardType: String = CardTypes.HEADING

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot) {
        val heading = card.raw["heading"]?.jsonPrimitive?.content
            ?: card.raw["title"]?.jsonPrimitive?.content
            ?: ""
        val style = when (card.raw["heading_style"]?.jsonPrimitive?.content) {
            "subtitle" -> HaHeadingStyle.Subtitle
            else -> HaHeadingStyle.Title
        }
        RemoteHaHeading(HaHeadingData(title = heading.rs, style = style))
    }
}
