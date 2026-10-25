package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DashboardDataTest {
  private val json = Json { ignoreUnknownKeys = true }

  private val dashboard =
    json.decodeFromString<Dashboard>(
      """
      {"views":[{"type":"sections","sections":[{"cards":[
        {"type":"history-graph","hours_to_show":48,"entities":["sensor.power",{"entity":"sensor.temp"}]},
        {"type":"grid","cards":[{"type":"history-graph","entities":["sensor.temp"]}]},
        {"type":"conditional","conditions":[],"card":{"type":"history-graph","hours_to_show":2,"entities":["binary_sensor.door"]}},
        {"type":"custom:remote-compose-card","card":{"type":"history-graph","hours_to_show":9999,"entities":["sensor.week"]}},
        {"type":"weather-forecast","entity":"weather.home","forecast_type":"daily"},
        {"type":"weather-forecast","entity":"weather.office","forecast_type":"hourly"},
        {"type":"weather-forecast","entity":"weather.cabin"},
        {"type":"weather-forecast","entity":"weather.quiet","show_forecast":false},
        {"type":"tile","entity":"sensor.other"}
      ]}]}]}
      """
    )

  @Test
  fun everyHistoryGraphEntityWithItsLongestWindow() {
    assertEquals(
      mapOf(
        "sensor.power" to 48,
        "sensor.temp" to 48,
        "binary_sensor.door" to 2,
        "sensor.week" to MAX_HISTORY_HOURS,
      ),
      historyWindows(dashboard),
    )
  }

  @Test
  fun everyWeatherCardsForecastByType() {
    assertEquals(
      mapOf("daily" to setOf("weather.home", "weather.cabin"), "hourly" to setOf("weather.office")),
      forecastRequests(dashboard),
    )
  }

  @Test
  fun forecastsAreHeldByEntityThenType() {
    val daily = JsonArray(listOf(JsonPrimitive("d")))
    val hourly = JsonArray(listOf(JsonPrimitive("h")))
    assertEquals(
      mapOf("weather.home" to JsonObject(mapOf("daily" to daily, "hourly" to hourly))),
      forecastsByEntity(
        mapOf(
          "daily" to mapOf("weather.home" to daily),
          "hourly" to mapOf("weather.home" to hourly),
        )
      ),
    )
  }

  @Test
  fun aSeriesEndsAtTheLiveState() {
    val fetched = listOf(HistoryPoint(Instant.fromEpochSeconds(100), "20"))
    val live = EntityState("sensor.temp", "21.5", lastUpdated = Instant.fromEpochSeconds(200))
    val snapshot =
      HaSnapshot(states = mapOf("sensor.temp" to live), history = mapOf("sensor.temp" to fetched))
    val tail = HistoryPoint(Instant.fromEpochSeconds(200), "21.5")
    assertEquals(fetched + tail, snapshot.withLiveHistoryTail().history["sensor.temp"])
    // Already ends there: nothing is added.
    val current = snapshot.copy(history = mapOf("sensor.temp" to fetched + tail))
    assertEquals(fetched + tail, current.withLiveHistoryTail().history["sensor.temp"])
  }

  @Test
  fun eachGraphPlotsOnlyItsOwnWindow() {
    val now = Instant.fromEpochSeconds(100 * 3600)
    fun at(hoursAgo: Int, value: String) = HistoryPoint(now - hoursAgo.hours, value)
    val snapshot =
      HaSnapshot(history = mapOf("sensor.temp" to listOf(at(40, "10"), at(5, "11"), at(1, "12"))))
    val twoHours =
      json.decodeFromString<CardConfig>(
        """{"type":"history-graph","hours_to_show":2,"entities":["sensor.temp"]}"""
      )
    assertEquals(
      listOf(at(2, "11"), at(1, "12")),
      snapshot.forCardHistoryWindow(twoHours, now).history["sensor.temp"],
    )
    val tile = json.decodeFromString<CardConfig>("""{"type":"tile","entity":"sensor.temp"}""")
    assertEquals(snapshot, snapshot.forCardHistoryWindow(tile, now))
  }
}
