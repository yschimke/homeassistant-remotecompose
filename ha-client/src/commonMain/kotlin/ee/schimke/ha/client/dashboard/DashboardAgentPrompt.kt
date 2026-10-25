package ee.schimke.ha.client.dashboard

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A dashboard and the home's entities written out for an agent, as the placeholder card's coding
 * prompt ([PlaceholderCardPrompt]) carries them.
 *
 * Dashboard edits by an agent no longer go through a copied prompt: they are edit sessions in the
 * Terrazzo integration (docs/architecture/dashboard-editing.md). The agent reads the dashboard and
 * the entities over the integration's MCP server, and the card guide from its `get_card_guide` tool
 * (`CARD_GUIDE` in `custom_components/terrazzo/mcp.py`, mirrored by
 * `skills/ha-dashboard/SKILL.md`).
 */
object DashboardAgentPrompt {
  /**
   * How many entities the prompt lists at most; a large home lists far more than a prompt needs.
   */
  const val MAX_ENTITIES = 400

  private val pretty = Json { prettyPrint = true }

  internal fun context(
    dashboard: JsonObject,
    states: Collection<EntityState>,
    title: String? = null,
  ): String = buildString {
    appendLine("## Current dashboard${title?.let { " ($it)" }.orEmpty()}")
    appendLine("```json")
    appendLine(pretty.encodeToString(JsonObject.serializer(), dashboard))
    appendLine("```")
    appendLine()
    appendLine("## Entities")
    appendLine("entity_id | name | state | unit | device_class")
    val listed = states.sortedBy { it.entityId }.take(MAX_ENTITIES)
    listed.forEach { appendLine(entityLine(it)) }
    if (states.size > listed.size) {
      appendLine("(${states.size - listed.size} more entities not listed)")
    }
  }

  private fun entityLine(state: EntityState): String {
    fun attr(name: String) =
      (state.attributes[name] as? JsonPrimitive)?.content?.replace('|', '/').orEmpty()
    return listOf(
        state.entityId,
        attr("friendly_name"),
        state.state.replace('|', '/'),
        attr("unit_of_measurement"),
        attr("device_class"),
      )
      .joinToString(" | ")
  }
}
