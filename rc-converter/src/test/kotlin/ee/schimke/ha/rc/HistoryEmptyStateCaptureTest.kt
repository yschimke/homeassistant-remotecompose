package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which cards show the history graph's single "No history in …" empty state: the `history-graph`
 * card does; the `sensor` card, which reuses the same layout for one row, keeps its current value.
 * Checked on the encoded document's text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryEmptyStateCaptureTest {

  private val registry = defaultRegistry()

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "sensor.temp" to
            EntityState(
              "sensor.temp",
              "21.5",
              JsonObject(
                mapOf(
                  "friendly_name" to JsonPrimitive("Living room"),
                  "unit_of_measurement" to JsonPrimitive("°C"),
                  "device_class" to JsonPrimitive("temperature"),
                )
              ),
            )
        )
    )

  private fun documentText(card: CardConfig): String = runBlocking {
    val doc =
      captureCardDocument(
        context = org.robolectric.RuntimeEnvironment.getApplication(),
        widthPx = 720,
        heightPx = 400,
        registry = registry,
        card = card,
        snapshot = snapshot,
        forceRefresh = true,
      )
    String(doc.bytes, Charsets.UTF_8)
  }

  @Test
  fun aSensorWithNoHistoryKeepsItsValue() {
    val text =
      documentText(
        CardConfig(
          type = "sensor",
          raw =
            buildJsonObject {
              put("type", "sensor")
              put("entity", "sensor.temp")
            },
        )
      )
    assertTrue("21.5" in text, "the sensor's current value is in the document")
    assertFalse("No history" in text, "a sensor card never shows the history empty state")
  }

  @Test
  fun aHistoryGraphWithNoHistoryShowsOneEmptyState() {
    val text =
      documentText(
        CardConfig(
          type = "history-graph",
          raw =
            buildJsonObject {
              put("type", "history-graph")
              put("hours_to_show", 48)
              putJsonArray("entities") { add("sensor.temp") }
            },
        )
      )
    assertTrue("No history in the last 2 days" in text)
  }
}
