@file:android.annotation.SuppressLint("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.JinjaTemplate
import ee.schimke.ha.model.TemplateBindings
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaMarkdownData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.Markdown
import ee.schimke.ha.rc.components.MarkdownBlock
import ee.schimke.ha.rc.components.RemoteHaMarkdown
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rs
import kotlinx.serialization.json.jsonPrimitive

class MarkdownCardConverter : CardConverter {
    override val cardType: String = CardTypes.MARKDOWN

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
        val content = card.raw["content"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()
        // Templates rendered by HA can expand/collapse vs. their source
        // (control flow, multi-line filters); size to the rendered text
        // when we have it, so the slot matches what's actually drawn.
        val text =
            if (TemplateBindings.needsServerRender(content)) {
                renderedTemplate(content, snapshot) ?: content
            } else {
                content
            }
        val lines = text.count { it == '\n' } + 1
        val title = if (card.raw["title"] != null) 32 else 0
        return title + 16 + 24 * lines // rough heuristic; title + top-pad + per-line.
    }

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
        val title = card.raw["title"]?.jsonPrimitive?.content
        val content = card.raw["content"]?.jsonPrimitive?.content ?: ""

        // Full Jinja templates (control flow, filters, state_attr, …)
        // can't be the simple per-expression binding. Prefer HA's own
        // render (snapshot.templates, populated by the live session);
        // otherwise fall back to the in-process JinjaTemplate evaluator
        // so previews / demo / offline still show real content. Either
        // way we parse the rendered markdown for full fidelity
        // (headings / bold / lists). The host folds the rendered value
        // into the document cache key, so new output re-encodes.
        if (TemplateBindings.needsServerRender(content)) {
            val rendered = renderedTemplate(content, snapshot)
            val blocks =
                if (rendered != null) {
                    Markdown.parse(rendered)
                } else {
                    // Evaluator couldn't handle it and there's no live
                    // render yet — placeholder beats dumping raw source.
                    listOf(MarkdownBlock(MarkdownBlock.Kind.Paragraph, "…"))
                }
            RemoteHaMarkdown(HaMarkdownData(title = title, blocks = blocks), modifier = modifier)
            return
        }

        val template = MarkdownTemplateBindings.from(content, snapshot)
        val blocks = Markdown.parse(template.rendered).bind(template)
        RemoteHaMarkdown(HaMarkdownData(title = title, blocks = blocks), modifier = modifier)
    }

    /**
     * The rendered output for a server-render template: HA's own render
     * if the live session supplied it, else the in-process evaluator's
     * best effort, else null.
     */
    private fun renderedTemplate(content: String, snapshot: HaSnapshot): String? =
        snapshot.templates[TemplateBindings.templateKey(content)]
            ?: JinjaTemplate.render(content, snapshot)

    private fun List<MarkdownBlock>.bind(template: MarkdownTemplateBindings): List<MarkdownBlock> =
        map { block ->
            block.copy(boundText = template.bind(block.text))
        }

    internal data class MarkdownTemplateBindings(
        val rendered: String,
        private val bindings: List<Binding>,
    ) {
        data class Binding(val token: String, val entityId: String, val initial: String)

        fun bind(text: String): RemoteString? {
            val tokenRegex = Regex("__HA_EXPR_\\d+__")
            if (!tokenRegex.containsMatchIn(text)) return null
            var expr: RemoteString = "".rs
            var cursor = 0
            tokenRegex.findAll(text).forEach { match ->
                if (match.range.first > cursor) expr += text.substring(cursor, match.range.first)
                val binding = bindings.firstOrNull { it.token == match.value }
                expr += if (binding != null) {
                    LiveValues.state(binding.entityId, binding.initial)
                } else {
                    match.value.rs
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) expr += text.substring(cursor)
            return expr
        }

        companion object {
            fun from(content: String, snapshot: HaSnapshot): MarkdownTemplateBindings {
                if (!content.contains("{{")) return MarkdownTemplateBindings(content, emptyList())
                val expr = Regex("\\{\\{\\s*(.*?)\\s*\\}\\}", RegexOption.DOT_MATCHES_ALL)
                val bindings = mutableListOf<Binding>()
                var index = 0
                val rendered = expr.replace(content) { match ->
                    val body = match.groupValues[1]
                    val entityId = Regex("states\\('([^']+)'\\)").find(body)?.groupValues?.get(1)
                    if (entityId != null) {
                        val token = "__HA_EXPR_${index++}__"
                        val initial = snapshot.states[entityId]?.state ?: "—"
                        bindings += Binding(token = token, entityId = entityId, initial = initial)
                        token
                    } else {
                        match.value
                    }
                }
                return MarkdownTemplateBindings(rendered, bindings)
            }
        }
    }
}
