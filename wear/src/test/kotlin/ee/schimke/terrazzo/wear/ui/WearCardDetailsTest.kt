package ee.schimke.terrazzo.wear.ui

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HistoryPoint
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A card's details screen: the chart it draws from history, and the facts it lists. */
class WearCardDetailsTest {
  private val end = Instant.parse("2026-10-25T12:00:00Z")
  private val start = end - 24.hours

  private fun at(hoursAgo: Int, state: String) = HistoryPoint(end - hoursAgo.hours, state)

  @Test
  fun numbersDrawAStepLineHeldToNow() {
    val chart = historyChart(listOf(at(24, "18"), at(12, "unavailable"), at(6, "22")), start, end)
    val line = chart as HistoryChart.Line
    assertEquals(18f, line.min)
    assertEquals(22f, line.max)
    // 18 from the start, stepping to 22 a quarter of the way from the end, held to now.
    assertEquals(listOf(0f to 18f, 0.75f to 18f, 0.75f to 22f, 1f to 22f), line.points)
  }

  @Test
  fun statesDrawATimelineOfRuns() {
    val chart = historyChart(listOf(at(30, "off"), at(12, "on"), at(6, "off")), start, end)
    assertEquals(
      HistoryChart.Timeline(
        listOf(
          HistoryChart.Segment(0f, 0.5f, "off"),
          HistoryChart.Segment(0.5f, 0.75f, "on"),
          HistoryChart.Segment(0.75f, 1f, "off"),
        )
      ),
      chart,
    )
  }

  @Test
  fun nothingToDrawIsEmpty() {
    assertEquals(HistoryChart.Empty, historyChart(emptyList(), start, end))
  }

  @Test
  fun theDetailsCoverTheCardsEntitiesMainOneFirst() {
    val entities =
      CardSummary(
        type = "entities",
        primaryEntityId = "light.hall",
        rawJson =
          """{"type":"entities","entities":["sensor.outside","light.hall",{"entity":"lock.front"}]}""",
      )
    assertEquals(listOf("light.hall", "sensor.outside", "lock.front"), detailsEntityIds(entities))
    val raw = CardSummary(type = "tile", rawJson = """{"type":"tile","entity":"sensor.outside"}""")
    assertEquals(listOf("sensor.outside"), detailsEntityIds(raw))
    assertEquals(emptyList(), detailsEntityIds(CardSummary(type = "markdown", rawJson = "{}")))
  }

  @Test
  fun theTitleIsTheCardsElseItsEntitysName() {
    val named = CardSummary(type = "tile", title = "Hall", primaryEntityId = "light.hall")
    assertEquals("Hall", detailsTitle(named, "light.hall", null))
    val untitled = CardSummary(type = "tile")
    assertEquals(
      "Outside",
      detailsTitle(untitled, "sensor.outside", EntityValue(friendlyName = "Outside")),
    )
    assertEquals("sensor.outside", detailsTitle(untitled, "sensor.outside", null))
  }

  @Test
  fun syncedFactsListTheStateTypeAndAttributes() {
    val value =
      EntityValue(state = "on", deviceClass = "garage_door", attributes = mapOf("remain" to "40"))
    assertEquals(
      listOf("State" to "On", "Type" to "garage door", "Remain" to "40"),
      entityFacts("cover.garage", value, null, end),
    )
    assertEquals(listOf("State" to "Not synced yet"), entityFacts("cover.garage", null, null, end))
  }

  @Test
  fun liveFactsAddWhenItChangedAndTheWellKnownAttributesFormatted() {
    val live =
      EntityState(
        "light.hall",
        "on",
        buildJsonObject {
          put("friendly_name", "Hall")
          put("brightness", 128)
          put("color_temp_kelvin", 2700)
          put("supported_color_modes", JsonArray(listOf(JsonPrimitive("color_temp"))))
          put("access_token", "secret")
          put("zone", "upstairs")
        },
        lastChanged = end - 3.hours,
      )
    assertEquals(
      listOf(
        "State" to "On",
        "Changed" to "3 h ago",
        "Brightness" to "50 %",
        "Colour temperature" to "2700 K",
        "Zone" to "upstairs",
      ),
      entityFacts("light.hall", EntityValue(state = "on"), live, end),
    )
  }

  @Test
  fun aSecretIsNeverListed() {
    val camera =
      EntityState("camera.door", "idle", buildJsonObject { put("access_token", "secret") })
    assertEquals(emptyList(), liveAttributeFacts(camera))
  }

  @Test
  fun agesReadShort() {
    assertEquals("now", ago(20.seconds))
    assertEquals("5 min ago", ago(5.minutes))
    assertEquals("2 d ago", ago(50.hours))
  }

  @Test
  fun aFullScreenCardFillsTheRoundScreensInscribedWidthWithinItsSpace() {
    // A 227 dp round watch, the page giving the card 140 dp of height: width-limited.
    assertEquals(227 * 0.87f / 200, fullScreenScale(200f, 112f, 227f, 140f, 227f, true), 0.001f)
    // Little height: height-limited.
    assertEquals(100f / 112, fullScreenScale(200f, 112f, 227f, 100f, 227f, true), 0.001f)
    // In a list (unbounded height) on a square screen: the full width.
    assertEquals(1.2f, fullScreenScale(200f, 112f, 240f, Float.POSITIVE_INFINITY, 240f, false))
    // Never past 1.6 times; below 1 (a small round watch) the card is laid out narrower instead.
    assertEquals(1.6f, fullScreenScale(200f, 112f, 600f, 600f, 600f, false))
    assertTrue(fullScreenScale(200f, 112f, 192f, Float.POSITIVE_INFINITY, 192f, true) < 1f)
  }
}
