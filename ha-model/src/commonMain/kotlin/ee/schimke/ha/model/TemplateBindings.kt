package ee.schimke.ha.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Jinja2 template found in a card, paired with a stable [key].
 *
 * The key de-duplicates identical templates (a dashboard that repeats a template renders it once)
 * and keys the per-card cache-key map the host builds from [JinjaTemplate] output.
 */
data class TemplateRef(val key: String, val template: String)

/**
 * Detection + stable keying for HA `markdown` card templates.
 *
 * A markdown card's `content:` may be a Jinja2 template. The trivial `{{ states('entity') }}`
 * interpolation is handled by the per-expression live binding in
 * `MarkdownCardConverter.MarkdownTemplateBindings`; anything richer — control flow (`{% … %}`),
 * comments (`{# … #}`), filters, or `state_attr(...)` — is evaluated in-process by [JinjaTemplate].
 * [needsTemplateRender] flags exactly the cards that take the [JinjaTemplate] path.
 */
object TemplateBindings {
  private val exprRegex = Regex("\\{\\{\\s*(.*?)\\s*\\}\\}", RegexOption.DOT_MATCHES_ALL)
  private val simpleStates = Regex("^states\\('[^']+'\\)$")

  /**
   * True when [content] uses Jinja that the simple per-expression binding can't represent, so it
   * must go through the [JinjaTemplate] evaluator:
   * - any statement block `{% … %}` or comment `{# … #}`, or
   * - any `{{ … }}` expression that isn't a bare `states('id')`.
   */
  fun needsTemplateRender(content: String): Boolean {
    if (content.contains("{%") || content.contains("{#")) return true
    for (match in exprRegex.findAll(content)) {
      if (!simpleStates.matches(match.groupValues[1].trim())) return true
    }
    return false
  }

  /**
   * Stable content-derived key for a template (FNV-1a over the trimmed source, hex). Two cards
   * sharing a template share a key.
   */
  fun templateKey(content: String): String {
    var hash = -0x7ee3623b // 0x811c9dc5 (FNV-1a 32-bit offset basis)
    for (ch in content.trim()) {
      hash = hash xor ch.code
      hash *= 0x01000193
    }
    return hash.toUInt().toString(16)
  }

  /**
   * Server-render templates referenced anywhere within a single card, recursing into nested cards
   * (stacks, conditional, grid, picture-elements). De-duplicated by [TemplateRef.key].
   */
  fun cardTemplates(card: CardConfig): List<TemplateRef> {
    val out = LinkedHashMap<String, TemplateRef>()
    collect(card.raw, out)
    return out.values.toList()
  }

  /**
   * Every template across a dashboard's views and sections that needs the [JinjaTemplate]
   * evaluator, de-duplicated by [TemplateRef.key].
   */
  fun dashboardTemplates(dashboard: Dashboard): List<TemplateRef> {
    val out = LinkedHashMap<String, TemplateRef>()
    for (view in dashboard.views) {
      for (card in view.cards) collect(card.raw, out)
      for (section in view.sections) {
        for (card in section.cards) collect(card.raw, out)
      }
    }
    return out.values.toList()
  }

  private fun collect(element: JsonElement, out: MutableMap<String, TemplateRef>) {
    when (element) {
      is JsonObject -> {
        val type = (element["type"] as? JsonPrimitive)?.content
        if (type == CardTypes.MARKDOWN) {
          val content = (element["content"] as? JsonPrimitive)?.content
          if (content != null && needsTemplateRender(content)) {
            val key = templateKey(content)
            out.getOrPut(key) { TemplateRef(key, content) }
          }
        }
        element.values.forEach { collect(it, out) }
      }
      is JsonArray -> element.forEach { collect(it, out) }
      else -> {}
    }
  }
}
