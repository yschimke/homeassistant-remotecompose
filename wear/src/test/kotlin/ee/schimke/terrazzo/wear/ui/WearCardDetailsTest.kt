package ee.schimke.terrazzo.wear.ui

import ee.schimke.ha.model.HistoryPoint
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

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
  fun theDetailsAreAboutTheCardsEntityAndTitledByTheCard() {
    val named = CardSummary(type = "tile", title = "Hall", primaryEntityId = "light.hall")
    assertEquals("light.hall", detailsEntityId(named))
    val raw = CardSummary(type = "tile", rawJson = """{"type":"tile","entity":"sensor.outside"}""")
    assertEquals("sensor.outside", detailsEntityId(raw))
    assertNull(detailsEntityId(CardSummary(type = "markdown", rawJson = "{}")))
    assertEquals("Hall", detailsTitle(named, "light.hall", null))
    assertEquals(
      "Outside",
      detailsTitle(raw, "sensor.outside", EntityValue(friendlyName = "Outside")),
    )
  }

  @Test
  fun theFactsListTheStateTypeAttributesAndId() {
    val value =
      EntityValue(state = "on", deviceClass = "garage_door", attributes = mapOf("remain" to "40"))
    assertEquals(
      listOf(
        "State" to "On",
        "Type" to "garage door",
        "Remain" to "40",
        "Entity" to "cover.garage",
      ),
      detailsAttributes("cover.garage", value),
    )
    assertEquals(listOf("State" to "Not synced yet"), detailsAttributes("cover.garage", null))
  }
}
