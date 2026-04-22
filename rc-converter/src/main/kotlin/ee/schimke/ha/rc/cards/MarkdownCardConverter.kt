package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaMarkdownData
import ee.schimke.ha.rc.components.RemoteHaMarkdown
import kotlinx.serialization.json.jsonPrimitive

class MarkdownCardConverter : CardConverter {
    override val cardType: String = CardTypes.MARKDOWN

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val content = card.raw["content"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()
        val lines = content.count { it == '\n' } + 1
        val title = if (card.raw["title"] != null) 32 else 0
        return title + 16 + 24 * lines // rough heuristic; title + top-pad + per-line.
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val content = card.raw["content"]?.jsonPrimitive?.content ?: ""
        val lines = content.split('\n').filter { it.isNotBlank() }.map { it.rs }
        RemoteHaMarkdown(HaMarkdownData(title = title?.rs, lines = lines), modifier = modifier)
    }
}
