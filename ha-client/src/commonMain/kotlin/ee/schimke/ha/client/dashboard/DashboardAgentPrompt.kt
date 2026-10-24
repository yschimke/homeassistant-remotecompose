package ee.schimke.ha.client.dashboard

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The hand-off between harc and the user's own agent for authoring a dashboard: a self-contained
 * prompt carrying the dashboard's current Lovelace config and the home's entities, and a parser for
 * the config the agent answers with. harc then saves that config to Home Assistant, which stays the
 * source of truth (see `HaClient.replaceDashboardConfig`).
 *
 * The guidance mirrors `skills/ha-dashboard/SKILL.md`, the same instructions packaged as a skill
 * for agents that load skills.
 */
object DashboardAgentPrompt {
  /**
   * How many entities the prompt lists at most; a large home lists far more than a prompt needs.
   */
  const val MAX_ENTITIES = 400

  private val pretty = Json { prettyPrint = true }

  val GUIDE: String =
    """
    You edit one Home Assistant Lovelace dashboard for harc, a dashboard viewer for phones, tablets,
    desktops, launcher widgets and watches.

    Rules:
    - Answer with the complete new dashboard config as one JSON object in a ```json fenced block,
      and nothing else in that block. Keep every view, section and card the user didn't ask to change.
    - Use only entity ids from the entity list below. Never invent entities.
    - Prefer these card types, which harc draws natively: tile, entities, glance, gauge, sensor,
      thermostat, humidifier, light, button, entity, history-graph, statistics-graph, statistic,
      weather-forecast, media-control, alarm-panel, area, picture-entity, markdown, heading,
      todo-list, calendar, logbook, map, clock, vertical-stack, horizontal-stack, grid,
      conditional, entity-filter.
    - Lay views out with `"type": "sections"` and group related cards into sections with a heading.
      harc shows sections in one column on a phone and side by side on wider screens.
    - Give every card a short `name` or `title` when the entity's own name is unclear.
    - Do not add automations, scripts, scenes or helpers: harc only edits dashboards.
    """
      .trimIndent()

  /**
   * How a card's size is written, for an agent laying out the editor's draft. Matches
   * [DashboardDraft]'s resize, and HA's section grid ([ee.schimke.ha.model.SectionCardGrid]).
   */
  val SIZING: String =
    """
    Card sizes (sections views):
    - Each section is a 12-column grid. Size a card with `"grid_options": {"columns": N, "rows": R}`:
      columns 3, 6, 9 or "full" (a quarter, half, three quarters or all of the section), rows a
      number of 56 dp rows or "auto" for the card's natural height.
    - On a phone a section is about 400 dp wide: 3 columns is about 95 dp, 6 about 195 dp.
      Two tiles share a row at 6 columns each; gauges, graphs, forecasts and lists read best "full".
    - Cards fill each section's rows in order, left to right, so order the cards the way they
      should read and give neighbours widths that add up to 12.
    """
      .trimIndent()

  /**
   * How an agent can look at harc's card designs before choosing them: the hosted Compose Preview
   * catalog (the `compose-preview-catalog` MCP server in this repository's `.mcp.json`), which
   * publishes every card design as a preview it can render at other sizes.
   */
  val DESIGN_TOOLS: String =
    """
    Seeing harc's card designs:
    - harc's card designs are published as previews in the hosted Compose Preview catalog, an MCP
      server at https://preview.coo.ee/mcp (catalog id `homeassistant-remotecompose`). If you can
      add MCP servers, add it; reading previews needs a short-lived grant: call `request_access`,
      show the person its `approveUrl` and `userCode`, then poll `poll_access` until it is
      approved and pass the token it returns as the `token` argument.
    - `catalog_list_previews` with `query` `card-<type>` (for example `card-gauge`, `card-tile`,
      `card-thermostat`, `card-history`) lists a card type's designs, light and dark.
    - `catalog_render_preview` with `observe: "png"` shows one. To see how a card reads at another
      width, render it with the `device` override (an id from `catalog_list_devices`, such as
      `id:small_phone` at 360 dp or `id:pixel_fold` at 841 dp), or compare several widths at once
      with `catalog_render_matrix` and `axes: {"device": [...]}`.
    - Use what you see to pick each card's type and size. If you can't reach the catalog, go by
      the card types and sizes above.
    """
      .trimIndent()

  /**
   * The prompt for [request] against [dashboard] (its current Lovelace config), listing [states]'
   * entities. [title] names the dashboard for the agent. [withDesignTools] adds [SIZING] and
   * [DESIGN_TOOLS], for the dashboard editor's handoff, where the agent populates a draft the user
   * then arranges.
   */
  fun build(
    request: String,
    dashboard: JsonObject,
    states: Collection<EntityState>,
    title: String? = null,
    withDesignTools: Boolean = false,
  ): String = buildString {
    appendLine(GUIDE)
    appendLine()
    if (withDesignTools) {
      appendLine(SIZING)
      appendLine()
      appendLine(DESIGN_TOOLS)
      appendLine()
    }
    appendLine("## Request")
    appendLine(request.trim().ifEmpty { "Improve this dashboard." })
    appendLine()
    append(context(dashboard, states, title))
  }

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

  /**
   * The dashboard config in an agent's [answer]: its first ```json fenced block, or the whole
   * answer when it is a bare JSON object. It must be an object with a `views` array; otherwise this
   * throws [IllegalArgumentException] with a message the user can act on.
   */
  fun parseAnswer(answer: String): JsonObject {
    val fenced = FENCE.find(answer)?.groupValues?.get(1)
    val text = (fenced ?: answer).trim()
    val element = runCatching {
      Json.parseToJsonElement(text)
    }
      .getOrElse { throw IllegalArgumentException("The answer has no valid JSON dashboard.") }
    val config =
      element as? JsonObject
        ?: throw IllegalArgumentException("The dashboard must be a JSON object.")
    require(config["views"] is JsonArray) { "The dashboard has no \"views\" list." }
    config["views"]!!.let { views ->
      require((views as JsonArray).all { runCatching { it.jsonObject }.isSuccess }) {
        "Every view must be a JSON object."
      }
    }
    return config
  }

  private val FENCE = Regex("```(?:json)?\\s*\\n([\\s\\S]*?)\\n```")
}
