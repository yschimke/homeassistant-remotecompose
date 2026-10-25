package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class DashboardHistoryTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun everyHistoryGraphEntityWithItsLongestWindow() {
    val dashboard =
      json.decodeFromString<Dashboard>(
        """
        {"views":[{"type":"sections","sections":[{"cards":[
          {"type":"history-graph","hours_to_show":48,"entities":["sensor.power",{"entity":"sensor.temp"}]},
          {"type":"grid","cards":[{"type":"history-graph","entities":["sensor.temp"]}]},
          {"type":"conditional","conditions":[],"card":{"type":"history-graph","hours_to_show":2,"entities":["binary_sensor.door"]}},
          {"type":"custom:remote-compose-card","card":{"type":"history-graph","hours_to_show":9999,"entities":["sensor.week"]}},
          {"type":"tile","entity":"sensor.other"}
        ]}]}]}
        """
      )
    assertEquals(
      mapOf(
        "sensor.power" to 48,
        "sensor.temp" to 48,
        "binary_sensor.door" to 2,
        "sensor.week" to 168,
      ),
      historyWindows(dashboard),
    )
  }

  @Test
  fun aSeriesEndsAtTheLiveState() {
    val fetched = listOf(HistoryPoint(Instant.fromEpochSeconds(100), "20"))
    val snapshot =
      HaSnapshot(
        states =
          mapOf(
            "sensor.temp" to
              EntityState("sensor.temp", "21.5", lastUpdated = Instant.fromEpochSeconds(200))
          )
      )
    assertEquals(
      fetched + HistoryPoint(Instant.fromEpochSeconds(200), "21.5"),
      withHistory(snapshot, mapOf("sensor.temp" to fetched)).history["sensor.temp"],
    )
    // Already ends there (or later): nothing is added.
    val current = fetched + HistoryPoint(Instant.fromEpochSeconds(200), "21.5")
    assertEquals(
      current,
      withHistory(snapshot, mapOf("sensor.temp" to current)).history["sensor.temp"],
    )
  }
}
