@file:android.annotation.SuppressLint("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.JinjaTemplate
import ee.schimke.ha.model.TemplateBindings
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaMarkdownData
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.Markdown
import ee.schimke.ha.rc.components.MarkdownBlock
import ee.schimke.ha.rc.components.MarkdownInline
import ee.schimke.ha.rc.components.RemoteHaMarkdown
import ee.schimke.ha.rc.components.RemoteHaMarkdownIdentity
import kotlinx.serialization.json.jsonPrimitive

class MarkdownCardConverter : CardConverter {
  override val cardType: String = CardTypes.MARKDOWN

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int {
    val content =
      card.raw["content"]
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        .orEmpty()
    // A rendered template can expand/collapse vs. its source (control
    // flow, multi-line filters); size to the rendered text when the
    // evaluator can produce it, so the slot matches what's drawn.
    val text =
      if (TemplateBindings.needsTemplateRender(content)) {
        JinjaTemplate.render(content, snapshot) ?: content
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
    // can't be the simple per-expression binding, so evaluate them
    // in-process with JinjaTemplate and parse the rendered markdown
    // for full fidelity (headings / bold / lists). Anything the
    // evaluator can't handle falls back to a placeholder rather than
    // dumping raw source. The host folds the rendered value into the
    // document cache key, so new output re-encodes.
    if (TemplateBindings.needsTemplateRender(content)) {
      val rendered = JinjaTemplate.render(content, snapshot)
      val blocks =
        if (rendered != null) {
          Markdown.parse(rendered)
        } else {
          listOf(MarkdownBlock(MarkdownBlock.Kind.Paragraph, "…"))
        }
      RenderSized(HaMarkdownData(title = title, blocks = blocks), modifier)
      return
    }

    val template = MarkdownTemplateBindings.from(content, snapshot)
    val blocks = Markdown.parse(template.rendered).bind(template)
    RenderSized(HaMarkdownData(title = title, blocks = blocks), modifier)
  }

  /**
   * Shared sizing branch for both the template and simple-binding paths. Dashboard renders the full
   * block list; launcher / Wear narrow cells drop to the identity tier (title + first line) and
   * wider ones render the full body. Single width gate (#224).
   */
  @Composable
  private fun RenderSized(data: HaMarkdownData, modifier: RemoteModifier) {
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> RemoteHaMarkdown(data, modifier = modifier)
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(BULK_IDENTITY_THRESHOLD_DP),
          modifier = modifier,
          axis = BreakpointAxis.Width,
        ) { tier ->
          if (tier == 0) {
            RemoteHaMarkdownIdentity(data, RemoteModifier.fillMaxWidth())
          } else {
            RemoteHaMarkdown(data, RemoteModifier.fillMaxWidth())
          }
        }
    }
  }

  private fun List<MarkdownBlock>.bind(template: MarkdownTemplateBindings): List<MarkdownBlock> =
    map { block ->
      // Bind the whole-block text (the single-RemoteText path) *and*
      // each inline run — a line that mixes a {{ states('x') }} token
      // with a link/image takes the rich flow-row path, which renders
      // per inline and would otherwise draw the literal token and stop
      // live-updating. template.bind returns null for token-free runs.
      block.copy(
        boundText = template.bind(block.text),
        inlines =
          block.inlines.map { inline ->
            when (inline) {
              is MarkdownInline.Text -> inline.copy(boundText = template.bind(inline.text))
              is MarkdownInline.Link -> inline.copy(boundText = template.bind(inline.text))
              is MarkdownInline.Image -> inline
            }
          },
      )
    }

  internal data class MarkdownTemplateBindings(
    val rendered: String,
    private val bindings: List<Binding>,
  ) {
    data class Binding(val token: String, val entityId: String, val initial: String)

    fun bind(text: String): RemoteString? {
      if (!EXPR_TOKEN_REGEX.containsMatchIn(text)) return null
      var expr: RemoteString = "".rs
      var cursor = 0
      EXPR_TOKEN_REGEX.findAll(text).forEach { match ->
        if (match.range.first > cursor) expr += text.substring(cursor, match.range.first)
        val binding = bindings.firstOrNull { it.token == match.value }
        expr +=
          if (binding != null) {
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
        val rendered =
          expr.replace(content) { match ->
            val body = match.groupValues[1]
            val entityId = Regex("states\\('([^']+)'\\)").find(body)?.groupValues?.get(1)
            if (entityId != null) {
              val token = haExprToken(index++)
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

/**
 * Binding-token sentinels for the simple `{{ states('x') }}` path.
 *
 * Private Use Area code points (U+E000 / U+E001) wrap the index so the token survives markdown
 * parsing untouched. The previous `__HA_EXPR_n__` form was eaten as bold emphasis (`__…__`), which
 * stripped the delimiters to `HA_EXPR_n` — the bind regex then missed it and the card showed the
 * literal text instead of the live state. PUA code points are never markdown control characters and
 * never occur in real content.
 */
internal const val HA_EXPR_OPEN: String = "\uE000"
internal const val HA_EXPR_CLOSE: String = "\uE001"

internal fun haExprToken(index: Int): String = "$HA_EXPR_OPEN$index$HA_EXPR_CLOSE"

private val EXPR_TOKEN_REGEX = Regex("$HA_EXPR_OPEN\\d+$HA_EXPR_CLOSE")
