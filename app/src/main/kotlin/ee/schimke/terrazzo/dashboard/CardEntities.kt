package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Best-effort extraction of the Home Assistant entity ids a card refers to, used by the
 * card-history screen to know what to chart. Covers the shapes the common card types use:
 *
 * - `entity: sensor.x` (tile, button, gauge, single-entity cards)
 * - `entities: [sensor.a, light.b]` (entities / glance / history-graph, bare-string form)
 * - `entities: [{ entity: sensor.a }, …]` (object form with per-row overrides)
 *
 * Order is preserved and duplicates are removed so a card that names the same entity twice (e.g.
 * once at top level, once in a row) charts it once. Cards with no recognisable entity reference
 * yield an empty list; the history screen then shows a "not linked to an entity" message rather
 * than an empty chart.
 */
fun CardConfig.historyEntityIds(): List<String> {
  val ids = LinkedHashSet<String>()
  raw["entity"]?.jsonPrimitive?.contentOrNull?.let { if (it.isEntityId()) ids += it }
  (raw["entities"] as? JsonArray)?.forEach { entry ->
    when (entry) {
      is JsonPrimitive -> entry.contentOrNull?.let { if (it.isEntityId()) ids += it }
      is JsonObject ->
        entry["entity"]?.jsonPrimitive?.contentOrNull?.let { if (it.isEntityId()) ids += it }
      else -> Unit
    }
  }
  return ids.toList()
}

/**
 * A human-readable title for the card, reusing the same key precedence the pin/section code uses
 * (`title` → `heading` → `name`), then a markdown card's first heading, before falling back to the
 * entity id, a container's cards' names, or the raw card type.
 */
fun CardConfig.historyTitle(): String =
  raw["title"]?.jsonPrimitive?.contentOrNull
    ?: raw["heading"]?.jsonPrimitive?.contentOrNull
    ?: raw["name"]?.jsonPrimitive?.contentOrNull
    ?: markdownHeading()
    ?: historyEntityIds().firstOrNull()
    ?: childrenTitle()
    ?: type

/**
 * An untitled container's (`grid`, a stack) title from its cards' names: "State, Garage light +2",
 * so its sheet and lists aren't titled "grid". Null when no child is named.
 */
private fun CardConfig.childrenTitle(): String? {
  val names =
    (raw["cards"] as? JsonArray)
      ?.mapNotNull { child ->
        (child as? JsonObject)?.let { obj ->
          listOf("name", "title", "heading", "entity").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
          }
        }
      }
      .orEmpty()
  if (names.isEmpty()) return null
  val shown = names.take(2).joinToString(", ")
  return if (names.size > 2) "$shown +${names.size - 2}" else shown
}

/**
 * A markdown card's first heading line (`## 🖨️ P2s` → `🖨️ P2s`), so its sheet isn't titled
 * "markdown". Skips headings that are templates, which would show their source.
 */
private fun CardConfig.markdownHeading(): String? {
  if (type != "markdown") return null
  val content = raw["content"]?.jsonPrimitive?.contentOrNull ?: return null
  return content
    .lineSequence()
    .map { it.trim() }
    .firstOrNull { it.startsWith("#") }
    ?.trimStart('#')
    ?.trim()
    ?.takeIf { it.isNotEmpty() && "{{" !in it && "{%" !in it }
    ?.take(60)
}

/** A string looks like an HA entity id when it's `domain.object_id`. */
private fun String.isEntityId(): Boolean {
  val dot = indexOf('.')
  return dot in 1 until length - 1
}
