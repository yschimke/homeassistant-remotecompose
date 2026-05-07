package ee.schimke.ha.previews

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.Section
import ee.schimke.ha.model.View
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loader for the per-dashboard JSON snapshots that live in the sibling
 * [homeassistant-agents](../../../homeassistant-agents) checkout under
 * `dashboards/<name>/{lovelace_config.json,entity_states.json}`. The
 * JSONs are sanitised in that repo (IPs / MACs / tokens / camera tokens
 * redacted) but entity ids and friendly names are kept — the
 * homeassistant-agents README treats those as non-secret.
 *
 * Files are deliberately *not* copied into this repo; the loader points
 * at the sibling checkout via [DASHBOARDS_ROOT]. When the directory
 * isn't present (CI, fresh clones), [load] returns null and the preview
 * function should skip rendering.
 */
object DashboardFixtures {
    /** Override via `-Dha.dashboards.root=/path/to/dashboards`. */
    private val DASHBOARDS_ROOT: File = File(
        System.getProperty("ha.dashboards.root")
            ?: "${System.getProperty("user.home")}/workspace/homeassistant-agents/dashboards",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Loaded(val dashboard: Dashboard, val snapshot: HaSnapshot)

    fun load(name: String): Loaded? {
        val dir = File(DASHBOARDS_ROOT, name)
        val configFile = File(dir, "lovelace_config.json")
        val statesFile = File(dir, "entity_states.json")
        if (!configFile.exists() || !statesFile.exists()) return null

        val dashboard = parseDashboard(configFile.readText())
        val states = parseStates(statesFile.readText())
        return Loaded(dashboard = dashboard, snapshot = HaSnapshot(states = states))
    }
}

/**
 * Parse a `lovelace/config` payload into [Dashboard]. The kotlinx
 * `@Serializable` deserializer doesn't populate [CardConfig.raw] from
 * the flat HA shape, so we walk the tree manually and stash the entire
 * card object into `raw` for converters to read.
 */
private fun parseDashboard(text: String): Dashboard {
    val root = Json.parseToJsonElement(text).jsonObject
    val title = root["title"]?.jsonPrimitive?.content
    val views = (root["views"] as? JsonArray)?.map { parseView(it.jsonObject) } ?: emptyList()
    return Dashboard(title = title, views = views)
}

private fun parseView(obj: JsonObject): View {
    val title = obj["title"]?.jsonPrimitive?.content
    val type = obj["type"]?.jsonPrimitive?.content
    val cards = (obj["cards"] as? JsonArray)?.mapNotNull { it.toCardConfig() } ?: emptyList()
    val sections = (obj["sections"] as? JsonArray)?.mapNotNull { el ->
        val s = el as? JsonObject ?: return@mapNotNull null
        Section(
            type = s["type"]?.jsonPrimitive?.content,
            title = s["title"]?.jsonPrimitive?.content,
            cards = (s["cards"] as? JsonArray)?.mapNotNull { e -> e.toCardConfig() } ?: emptyList(),
        )
    } ?: emptyList()
    return View(title = title, type = type, cards = cards, sections = sections)
}

private fun kotlinx.serialization.json.JsonElement.toCardConfig(): CardConfig? {
    val obj = this as? JsonObject ?: return null
    val type = obj["type"]?.jsonPrimitive?.content ?: return null
    return CardConfig(type = type, raw = obj)
}

private fun parseStates(text: String): Map<String, EntityState> {
    val root = Json.parseToJsonElement(text).jsonObject
    return root.entries.mapNotNull { (id, el) ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val state = obj["state"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val attrs = (obj["attributes"] as? JsonObject) ?: JsonObject(emptyMap())
        id to EntityState(entityId = id, state = state, attributes = attrs)
    }.toMap()
}
