package ee.schimke.ha.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Jinja2 template found in a card, paired with a stable [key].
 *
 * The key is the map key into [HaSnapshot.templates]: the host renders the template against the
 * live `hass` object (HA's WebSocket `render_template` command) and stores the result under [key];
 * a converter looks the result back up by recomputing the same key from the card's `content`.
 * Identical templates collapse to one key, so a dashboard that repeats a template renders it once.
 */
data class TemplateRef(val key: String, val template: String)

/**
 * Detection + stable keying for HA `markdown` card templates.
 *
 * HA's frontend evaluates a markdown card's `content:` through the server-side Jinja2 engine and
 * renders only the result. This library has no Jinja engine, so anything beyond the trivial `{{
 * states('entity') }}` interpolation — control flow (`{% … %}`), comments (`{# … #}`), filters, or
 * `state_attr(...)` — cannot be represented by the per-expression live binding in
 * `MarkdownCardConverter.MarkdownTemplateBindings`. [needsServerRender] flags exactly those cards
 * so the host can render them on HA and feed the result back via [HaSnapshot.templates].
 */
object TemplateBindings {
  private val exprRegex = Regex("\\{\\{\\s*(.*?)\\s*\\}\\}", RegexOption.DOT_MATCHES_ALL)
  private val simpleStates = Regex("^states\\('[^']+'\\)$")

  /**
   * True when [content] uses Jinja that the simple per-expression binding can't represent and so
   * must be rendered by HA:
   * - any statement block `{% … %}` or comment `{# … #}`, or
   * - any `{{ … }}` expression that isn't a bare `states('id')`.
   */
  fun needsServerRender(content: String): Boolean {
    if (content.contains("{%") || content.contains("{#")) return true
    for (match in exprRegex.findAll(content)) {
      if (!simpleStates.matches(match.groupValues[1].trim())) return true
    }
    return false
  }

  /**
   * Stable content-derived key for a template (FNV-1a over the trimmed source, hex). Deterministic
   * across processes so the host that renders and the converter that reads agree, and so two cards
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
   * Every server-render template across a dashboard's views and sections, de-duplicated by
   * [TemplateRef.key]. The live session renders each once per refresh and writes the results into
   * [HaSnapshot.templates].
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
          if (content != null && needsServerRender(content)) {
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
