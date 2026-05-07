package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.Section
import ee.schimke.ha.model.View
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Offline session for the "Demo mode" toggle — no login, no network.
 *
 * Delegates to [DemoData] for dashboards and state so pinned home-screen
 * widgets (which don't hold a session) can render against the same fake
 * data just by calling `DemoData.snapshot()`.
 */
class DemoHaSession(
    private val clock: () -> Long = System::currentTimeMillis,
) : HaSession {
    override val baseUrl: String = DemoData.BASE_URL

    /** Once a minute. The demo entities drift on a per-minute cadence so
     *  the user sees values change without burning battery. */
    override val refreshIntervalMillis: Long = 60_000L

    override suspend fun connect() = Unit
    override suspend fun listDashboards(): List<DashboardSummary> = DemoData.dashboards
    override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
        DemoData.dashboard(urlPath) to DemoData.snapshot(clock())
    override suspend fun close() = Unit
}

/**
 * Fake dashboard + entity state for demo mode. Powered by the seven
 * captured Lovelace dashboards under
 * `terrazzo-core/src/androidMain/resources/dashboards/<name>/` —
 * scrubbed twice (the `homeassistant-agents` repo's `_sanitize.py`
 * for IPs/MACs/tokens/lat-lon, then this repo's
 * `terrazzo-core/scripts/scrub-demo-dashboards.py` for entity-id
 * serials and friendly-name leaks).
 *
 * Numeric sensor values drift on a per-minute cadence so the in-app
 * demo and pinned widgets show animation — see [snapshot]. Toggle
 * states (lights, switches) flip on a few different cadences too.
 */
object DemoData {
    const val BASE_URL: String = "demo://terrazzo"

    fun isDemo(baseUrl: String?): Boolean = baseUrl == BASE_URL

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Captured boards bundled in resources, in dashboard-picker order. */
    private val BOARDS: List<DemoBoard> = listOf(
        DemoBoard(slug = "security", title = "Security"),
        DemoBoard(slug = "3d_printing", title = "3D Printing"),
        DemoBoard(slug = "climate", title = "Climate"),
        DemoBoard(slug = "energy", title = "Energy"),
        DemoBoard(slug = "github", title = "GitHub"),
        DemoBoard(slug = "meshcore", title = "Meshcore"),
        DemoBoard(slug = "networks", title = "Networks"),
    )

    val dashboards: List<DashboardSummary> = BOARDS.mapIndexed { i, b ->
        DashboardSummary(
            urlPath = if (i == 0) null else b.slug.replace('_', '-'),
            title = b.title,
            icon = null,
        )
    }

    /** Pre-loaded dashboards (config + entity state) per slug. Reading
     *  resources is JVM-cheap but we cache anyway since the picker
     *  flips between boards frequently. */
    private val cachedDashboards: Map<String, Dashboard> = BOARDS.associate { b ->
        b.slug to loadDashboard(b)
    }

    private val baseStates: Map<String, EntityState> = BOARDS.flatMap { b ->
        loadStates(b).entries
    }.associate { it.key to it.value }

    fun dashboard(urlPath: String?): Dashboard {
        val slug = urlPathToSlug(urlPath)
        return cachedDashboards[slug]
            ?: cachedDashboards[BOARDS.first().slug]!!
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): HaSnapshot {
        val drifted = baseStates.mapValues { (id, st) -> drift(id, st, nowMs) }
        return HaSnapshot(states = drifted)
    }

    private fun urlPathToSlug(urlPath: String?): String {
        if (urlPath == null) return BOARDS.first().slug
        return BOARDS.firstOrNull {
            it.slug.replace('_', '-') == urlPath || it.slug == urlPath
        }?.slug ?: BOARDS.first().slug
    }

    private fun loadDashboard(b: DemoBoard): Dashboard {
        val text = readResource("/dashboards/${b.slug}/lovelace_config.json") ?: return emptyBoard(b)
        return parseDashboard(text, b.title)
    }

    private fun loadStates(b: DemoBoard): Map<String, EntityState> {
        val text = readResource("/dashboards/${b.slug}/entity_states.json") ?: return emptyMap()
        return parseStates(text)
    }

    private fun emptyBoard(b: DemoBoard): Dashboard = Dashboard(title = b.title)

    private fun readResource(path: String): String? =
        DemoData::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    private fun parseDashboard(text: String, fallbackTitle: String): Dashboard {
        val root = Json.parseToJsonElement(text).jsonObject
        val title = root["title"]?.jsonPrimitive?.content ?: fallbackTitle
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
                cards = (s["cards"] as? JsonArray)?.mapNotNull { c -> c.toCardConfig() } ?: emptyList(),
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

    /**
     * Per-minute drift: numeric sensors get a small sinusoidal offset
     * keyed by the entity id and the minute-bucket of `nowMs`, so each
     * minute brings new but plausible values. Lights and switches
     * toggle on coarser cadences (5/8/15 minutes) so the user sees the
     * dashboard "live" without a jittery, fast feel.
     */
    private fun drift(entityId: String, base: EntityState, nowMs: Long): EntityState {
        val minute = (nowMs / 60_000L).toInt()
        val phase = (entityId.hashCode().toLong() and 0xFFFFL).toDouble()
        val seed = (minute + phase / 1000.0)
        val unit = base.attributes["unit_of_measurement"]?.jsonPrimitive?.content
        val deviceClass = base.attributes["device_class"]?.jsonPrimitive?.content

        val originalNumber = base.state.toDoubleOrNull()
        val newState: String? = when {
            originalNumber != null && unit != null -> {
                val amplitude = when (deviceClass) {
                    "battery" -> 1.0
                    "humidity", "moisture" -> 1.5
                    "temperature" -> 0.4
                    "power", "energy", "current", "voltage" -> originalNumber * 0.08 + 5.0
                    "pressure" -> 0.6
                    "illuminance" -> originalNumber * 0.12 + 1.0
                    else -> originalNumber * 0.05 + 0.5
                }
                val drifted = clampForDeviceClass(
                    deviceClass,
                    originalNumber + amplitude * sin(seed * 0.7),
                )
                formatLikeOriginal(base.state, drifted)
            }
            base.state == "on" || base.state == "off" -> {
                if (entityId.startsWith("light.")) {
                    val toggle = (minute + (phase.toLong() % 7)) % 8 == 0L
                    if (toggle) flipBinary(base.state) else null
                } else if (entityId.startsWith("switch.")) {
                    val toggle = (minute + (phase.toLong() % 5)) % 15 == 0L
                    if (toggle) flipBinary(base.state) else null
                } else null
            }
            else -> null
        }
        return if (newState != null && newState != base.state) {
            base.copy(state = newState)
        } else base
    }

    private fun formatLikeOriginal(original: String, value: Double): String {
        val dotIndex = original.indexOf('.')
        val decimals = if (dotIndex == -1) 0 else original.length - dotIndex - 1
        return if (decimals == 0) value.roundToInt().toString()
        else "%.${decimals}f".format(value)
    }

    private fun flipBinary(state: String): String = if (state == "on") "off" else "on"

    private fun clampForDeviceClass(deviceClass: String?, value: Double): Double = when (deviceClass) {
        "battery", "humidity", "moisture" -> value.coerceIn(0.0, 100.0)
        "illuminance", "power", "energy", "current", "voltage" -> value.coerceAtLeast(0.0)
        "pressure" -> value.coerceAtLeast(900.0)
        else -> value
    }

    private data class DemoBoard(val slug: String, val title: String)
}
