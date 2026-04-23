package ee.schimke.terrazzo.core.session

import ee.schimke.ha.client.DashboardSummary
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.View
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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
    override val refreshIntervalMillis: Long = 2_000L

    override suspend fun connect() = Unit
    override suspend fun listDashboards(): List<DashboardSummary> = DemoData.dashboards
    override suspend fun loadDashboard(urlPath: String?): Pair<Dashboard, HaSnapshot> =
        DemoData.dashboard(urlPath) to DemoData.snapshot(clock())
    override suspend fun close() = Unit
}

/**
 * Fake dashboard + entity state for demo mode.
 *
 * Values drift with wall-clock time (sine-wave temperatures, toggling
 * lights, slowly draining battery) so the app shows animation instead
 * of a frozen frame. Shared between [DemoHaSession] (in-app dashboards)
 * and the widget provider (pinned home-screen widgets) — any widget
 * whose stored `baseUrl == DemoData.BASE_URL` renders against
 * [DemoData.snapshot] instead of the empty default.
 */
object DemoData {
    /** Marker baseUrl used to identify demo-mode artefacts (stored on
     *  pinned widgets so the provider knows to render demo state). */
    const val BASE_URL: String = "demo://terrazzo"

    fun isDemo(baseUrl: String?): Boolean = baseUrl == BASE_URL

    val dashboards: List<DashboardSummary> = listOf(
        DashboardSummary(urlPath = null, title = "Home", icon = null),
        DashboardSummary(urlPath = "downstairs", title = "Downstairs", icon = null),
        DashboardSummary(urlPath = "office", title = "Office", icon = null),
    )

    fun dashboard(urlPath: String?): Dashboard = when (urlPath) {
        "downstairs" -> Dashboard(title = "Downstairs", views = listOf(View(cards = downstairsCards)))
        "office" -> Dashboard(title = "Office", views = listOf(View(cards = officeCards)))
        else -> Dashboard(title = "Home", views = listOf(View(cards = homeCards)))
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): HaSnapshot {
        val tSec = nowMs / 1000.0

        // Slow sine wave → living-room temperature drifts 19.0–23.0°C.
        val livingTemp = 21.0 + 2.0 * sin(tSec / 60.0)
        // Faster wave → humidity 38–58%.
        val humidity = (48.0 + 10.0 * sin(tSec / 20.0)).roundToInt()
        // Power draw: 80–260W, noisy-looking sine.
        val power = (170.0 + 90.0 * sin(tSec / 7.0)).roundToInt()
        // Office temp: gentler 20–22°C.
        val officeTemp = 21.0 + 1.0 * sin(tSec / 90.0)
        // Laptop battery: drains 100% → 20% over 40 min, then resets.
        val batteryPct = 100 - ((nowMs / 1000L / 30L) % 81L).toInt()
        // Office lamp toggles every 8 s so you can see a light switch.
        val officeLampOn = (nowMs / 8_000L) % 2L == 0L
        // Hallway light pulses every 12 s.
        val hallwayOn = (nowMs / 12_000L) % 3L == 0L

        return HaSnapshot(
            states = mapOf(
                entity(
                    "sensor.living_room",
                    "%.1f".format(livingTemp),
                    "friendly_name" to "Living Room",
                    "unit_of_measurement" to "°C",
                    "device_class" to "temperature",
                ),
                entity(
                    "sensor.office_temp",
                    "%.1f".format(officeTemp),
                    "friendly_name" to "Office",
                    "unit_of_measurement" to "°C",
                    "device_class" to "temperature",
                ),
                entity(
                    "sensor.humidity",
                    humidity.toString(),
                    "friendly_name" to "Humidity",
                    "unit_of_measurement" to "%",
                    "device_class" to "humidity",
                ),
                entity(
                    "sensor.power",
                    power.toString(),
                    "friendly_name" to "Power",
                    "unit_of_measurement" to "W",
                    "device_class" to "power",
                ),
                entity(
                    "sensor.laptop_battery",
                    batteryPct.toString(),
                    "friendly_name" to "Laptop",
                    "unit_of_measurement" to "%",
                    "device_class" to "battery",
                ),
                entity(
                    "light.kitchen",
                    "on",
                    "friendly_name" to "Kitchen",
                    "brightness" to "220",
                ),
                entity(
                    "light.office_lamp",
                    if (officeLampOn) "on" else "off",
                    "friendly_name" to "Office lamp",
                    "brightness" to if (officeLampOn) "180" else "0",
                ),
                entity(
                    "light.hallway",
                    if (hallwayOn) "on" else "off",
                    "friendly_name" to "Hallway",
                ),
                entity(
                    "switch.coffee_maker",
                    "on",
                    "friendly_name" to "Coffee maker",
                ),
                entity(
                    "switch.desk_fan",
                    "off",
                    "friendly_name" to "Desk fan",
                ),
                entity(
                    "lock.front_door",
                    "locked",
                    "friendly_name" to "Front door",
                ),
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun card(src: String): CardConfig {
        val obj = json.parseToJsonElement(src).jsonObject
        val type = obj["type"]!!.toString().trim('"')
        return CardConfig(type = type, raw = obj)
    }

    private val homeCards = listOf(
        card("""{"type":"heading","heading":"Home"}"""),
        card("""{"type":"glance","title":"Overview","entities":[
            "sensor.living_room",
            "light.kitchen",
            "lock.front_door"
        ]}"""),
        card("""{"type":"entities","title":"Living Room","entities":[
            "sensor.living_room",
            "sensor.humidity",
            "light.kitchen",
            "switch.coffee_maker"
        ]}"""),
        card("""{"type":"vertical-stack","cards":[
            {"type":"tile","entity":"light.kitchen","color":"amber"},
            {"type":"tile","entity":"lock.front_door"}
        ]}"""),
    )

    private val downstairsCards = listOf(
        card("""{"type":"heading","heading":"Downstairs"}"""),
        card("""{"type":"grid","cards":[
            {"type":"button","entity":"light.kitchen","name":"Kitchen"},
            {"type":"button","entity":"light.hallway","name":"Hallway"},
            {"type":"button","entity":"switch.coffee_maker","name":"Coffee"},
            {"type":"button","entity":"lock.front_door","name":"Door","icon":"mdi:door"}
        ]}"""),
        card("""{"type":"entities","title":"Environment","entities":[
            "sensor.living_room",
            "sensor.humidity",
            "sensor.power"
        ]}"""),
    )

    private val officeCards = listOf(
        card("""{"type":"heading","heading":"Office"}"""),
        card("""{"type":"horizontal-stack","cards":[
            {"type":"button","entity":"light.office_lamp","name":"Lamp"},
            {"type":"button","entity":"switch.desk_fan","name":"Fan"}
        ]}"""),
        card("""{"type":"entities","title":"Desk","entities":[
            "sensor.office_temp",
            "sensor.laptop_battery",
            "light.office_lamp"
        ]}"""),
        card("""{"type":"markdown","title":"Notes","content":"Demo mode — values change every two seconds."}"""),
    )

    private fun entity(
        id: String,
        state: String,
        vararg attrs: Pair<String, String>,
    ): Pair<String, EntityState> {
        val obj = JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) })
        return id to EntityState(entityId = id, state = state, attributes = obj)
    }
}
