package ee.schimke.ha.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Snapshot of Home Assistant state that a card converter may read.
 *
 * Cards in HA pull live data from a mutable `hass` object — state of every
 * entity plus service handles. A converter operating outside that runtime
 * needs a pre-resolved snapshot: the entities the card cares about, their
 * history/statistics/forecast payloads, and enough registry info to resolve
 * names and units.
 */
@Serializable
data class HaSnapshot(
    val states: Map<String, EntityState> = emptyMap(),
    val themes: ThemeSet = ThemeSet(),
    val locale: Locale = Locale("en", "US"),
    val history: Map<String, List<HistoryPoint>> = emptyMap(),
    val statistics: Map<String, List<StatisticPoint>> = emptyMap(),
    val forecasts: Map<String, JsonObject> = emptyMap(),
)

@Serializable
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val lastChanged: Instant? = null,
    val lastUpdated: Instant? = null,
)

@Serializable
data class HistoryPoint(
    val ts: Instant,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class StatisticPoint(
    val start: Instant,
    val mean: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val sum: Double? = null,
    val state: Double? = null,
)

@Serializable
data class ThemeSet(
    val active: String? = null,
    val dark: Boolean = false,
    val tokens: Map<String, String> = emptyMap(),
)

@Serializable
data class Locale(
    val language: String,
    val country: String,
    val timeZone: String? = null,
)
