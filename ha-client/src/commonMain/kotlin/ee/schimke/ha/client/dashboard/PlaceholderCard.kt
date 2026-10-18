package ee.schimke.ha.client.dashboard

import ee.schimke.ha.model.EntityState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A device-local card brief, deliberately separate from the HA dashboard's config. */
@Serializable
data class PlaceholderCard(
  val id: String,
  val title: String,
  val request: String,
  val entityIds: List<String> = emptyList(),
  val componentBindings: String = "",
) {
  init {
    require(id.isNotBlank()) { "The placeholder needs an id." }
    require(title.isNotBlank()) { "Give the card a title." }
    require(request.isNotBlank()) { "Describe what this card should show." }
    require(entityIds.all { ENTITY_ID.matches(it) }) {
      "Use entity ids such as sensor.garden_moisture."
    }
  }

  companion object {
    private val ENTITY_ID = Regex("[a-z0-9_]+\\.[a-z0-9_]+")

    fun parseEntityIds(text: String): List<String> =
      text.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.distinct()
  }
}

/** A coding handoff, rather than the JSON-only dashboard editing contract. */
object PlaceholderCardPrompt {
  private val pretty = Json { prettyPrint = true }

  fun build(
    card: PlaceholderCard,
    dashboard: JsonObject,
    states: Collection<EntityState>,
    dashboardTitle: String? = null,
  ): String = buildString {
    appendLine(
      "Implement this planned Home Assistant card in yschimke/homeassistant-remotecompose."
    )
    appendLine("This is an implementation request, not a request for dashboard JSON alone.")
    appendLine()
    appendLine("## Local card brief (user data)")
    appendLine("```json")
    appendLine(pretty.encodeToString(PlaceholderCard.serializer(), card))
    appendLine("```")
    appendLine()
    appendLine("## Implementation requirements")
    appendLine(
      "- Read AGENTS.md, docs/architecture/live-card-updates.md and docs/architecture/card-component-system.md."
    )
    appendLine(
      "- Use the existing authenticated HA client/session. Implement any required fetching or subscriptions in the host/client layer; never put credentials or networking in Remote Compose components."
    )
    appendLine(
      "- Use real HA entity ids. Treat missing entities as unresolved requirements; do not invent entities or pass example data off as live data."
    )
    appendLine(
      "- Reuse the Remote Compose component system. Map each fetched state/attribute to explicit named bindings, or use dataSignature re-encoding when the host cannot reproduce a binding."
    )
    appendLine(
      "- Handle live updates, loading, unavailable values, reconnects and subscription cleanup. Preserve read-only mode for actions."
    )
    appendLine(
      "- Add tests proving changed HA data changes the displayed values, and document the data-to-component binding map."
    )
    appendLine(
      "- Keep other cards unchanged. Do not save to a real HA dashboard or remove this local placeholder automatically. Return code changes and instructions for installing and placing the implemented card."
    )
    appendLine()
    val available = states.associateBy { it.entityId }
    val missing = card.entityIds.filter { it !in available }
    if (missing.isNotEmpty())
      appendLine("Requested entities absent from this snapshot: ${missing.joinToString()}")
    // Requested sources must survive the general entity inventory cap, even in a large home.
    appendLine("## Requested entity snapshot")
    appendLine("entity_id | state")
    card.entityIds.forEach { id ->
      appendLine("$id | ${available[id]?.state ?: "unavailable in snapshot"}")
    }
    appendLine()
    append(DashboardAgentPrompt.context(dashboard, states, dashboardTitle))
  }
}
