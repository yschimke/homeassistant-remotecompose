package ee.schimke.ha.rc.cards

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SensorCardConverterTest {

  private val converter = SensorCardConverter()

  private val snapshot =
    HaSnapshot(
      states = mapOf("sensor.temp" to EntityState("sensor.temp", "21.5", JsonObject(emptyMap())))
    )

  private fun card(hours: Int? = null) =
    CardConfig(
      type = "sensor",
      raw =
        buildJsonObject {
          put("type", "sensor")
          put("entity", "sensor.temp")
          hours?.let { put("hours_to_show", it) }
        },
    )

  private fun range(card: CardConfig) =
    converter.designBindings(card, snapshot).strings["card.range"]

  @Test
  fun theRangeLabelDefaultsToHomeAssistantsDay() {
    assertEquals("Last 24 hours", range(card()))
  }

  @Test
  fun theRangeLabelReadsLikeTheHistoryGraphs() {
    for (hours in listOf(6, 24, 48, 168, 336, 720)) {
      val sensor = card(hours)
      assertEquals(historyRangeLabel(sensor), range(sensor), "hours_to_show: $hours")
    }
    assertEquals("Last 7 days", range(card(168)))
    assertEquals("Last 2 weeks", range(card(336)))
  }
}
