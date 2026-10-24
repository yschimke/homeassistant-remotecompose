package ee.schimke.ha.rc.cards

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.HistoryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class HistoryGraphCardConverterTest {

  private val converter = HistoryGraphCardConverter()

  private fun state(id: String, friendlyName: String? = null) =
    EntityState(
      id,
      "1",
      JsonObject(friendlyName?.let { mapOf("friendly_name" to JsonPrimitive(it)) } ?: emptyMap()),
    )

  private fun series(vararg values: Int) = values.mapIndexed { i, v ->
    HistoryPoint(Instant.fromEpochMilliseconds(i * 600_000L), v.toString())
  }

  private fun card(hours: Int? = null) =
    CardConfig(
      type = "history-graph",
      raw =
        buildJsonObject {
          put("type", "history-graph")
          hours?.let { put("hours_to_show", it) }
          putJsonArray("entities") {
            addJsonObject {
              put("entity", "sensor.meter_import")
              put("name", "Import")
            }
            add("sensor.solar_power")
            addJsonObject { put("entity", "sensor.no_name") }
          }
        },
    )

  private val snapshot =
    HaSnapshot(
      states =
        listOf(
            state(
              "sensor.meter_import",
              "Electricity Meter Export Current Rate Import Electricity",
            ),
            state("sensor.solar_power", "solaredge Current power"),
            state("sensor.no_name"),
          )
          .associateBy { it.entityId },
      history = mapOf("sensor.solar_power" to series(1, 2, 3)),
    )

  @Test
  fun aRowsOwnNameWinsOverTheFriendlyName() {
    assertEquals(
      listOf("Import", "solaredge Current power", "sensor.no_name"),
      historyRowNames(card(), snapshot),
    )
  }

  @Test
  fun templateBindingsCarryTheConfiguredNames() {
    val strings = converter.designBindings(card(48), snapshot).strings
    val import = CardDesignSlots.slotId("sensor", 0)
    val solar = CardDesignSlots.slotId("sensor", 1)
    assertEquals("Import", strings["$import.name"])
    assertEquals("solaredge Current power", strings["$solar.name"])
    assertEquals("Last 2 days", strings["card.range"])
    assertEquals("No history in the last 2 days", strings["card.empty"])
  }

  @Test
  fun aNameOverrideIsNotStructure() {
    val plain =
      CardConfig(
        type = "history-graph",
        raw =
          buildJsonObject {
            put("type", "history-graph")
            putJsonArray("entities") {
              add("sensor.meter_import")
              add("sensor.solar_power")
              add("sensor.no_name")
            }
          },
      )
    val named = assertNotNull(converter.designProperties(card(), snapshot))
    assertEquals(converter.designProperties(plain, snapshot), named)
  }

  @Test
  fun windowsReadLikeHomeAssistant() {
    assertEquals("last hour", historyWindow(1.0))
    assertEquals("last 6 hours", historyWindow(6.0))
    assertEquals("last 24 hours", historyWindow(24.0))
    assertEquals("last 36 hours", historyWindow(36.0))
    assertEquals("last 2 days", historyWindow(48.0))
    assertEquals("last 50 hours", historyWindow(50.0))
    assertEquals("last 3 days", historyWindow(72.0))
    assertEquals("last 7 days", historyWindow(168.0))
    assertEquals("last 2 weeks", historyWindow(336.0))
    assertEquals("last 3 weeks", historyWindow(504.0))
    assertEquals("last 30 days", historyWindow(720.0))
    assertEquals("last 30 minutes", historyWindow(0.5))
    assertEquals("last 1.5 hours", historyWindow(1.5))
  }

  @Test
  fun theSubtitleAndEmptyStateUseTheWindow() {
    assertEquals("Last 24 hours", historyRangeLabel(card()))
    assertEquals("Last 7 days", historyRangeLabel(card(168)))
    assertEquals("Last 2 weeks", historyRangeLabel(card(336)))
    assertEquals("No history in the last 30 days", historyEmptyLabel(card(720)))
  }

  @Test
  fun emptySeriesTakeLessRoom() {
    val allEmpty = HaSnapshot(states = snapshot.states)
    val partly = snapshot
    val full =
      snapshot.copy(
        history =
          mapOf(
            "sensor.meter_import" to series(1, 2),
            "sensor.solar_power" to series(1, 2),
            "sensor.no_name" to series(1, 2),
          )
      )
    val h = { s: HaSnapshot -> converter.naturalHeightDp(card(), s) }
    assertTrue(h(allEmpty) < h(partly), "all-empty ${h(allEmpty)} < partly ${h(partly)}")
    assertTrue(h(partly) < h(full), "partly ${h(partly)} < full ${h(full)}")
  }
}
