package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HistoryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MoreInfoHistoryTest {

  private fun at(minute: Long) = Instant.fromEpochMilliseconds(minute * 60_000L)

  private fun point(minute: Long, state: String) = HistoryPoint(at(minute), state)

  private fun state(minute: Long, value: String) =
    EntityState(entityId = "switch.boiler", state = value, lastUpdated = at(minute))

  @Test
  fun everyPushedStateStaysOnTheChart() {
    val fetched = listOf(point(0, "off"))
    // off → on → off while the sheet is open: the on run must survive the second change.
    val history =
      listOf(state(10, "on"), state(20, "off")).fold(fetched) { acc, s -> withLiveTail(acc, s) }

    assertEquals(listOf(point(0, "off"), point(10, "on"), point(20, "off")), history)
    assertEquals(2, changeCount(history))
    assertEquals(
      listOf("off" to 20 * 60_000L, "on" to 10 * 60_000L),
      timeInStates(history, endMs = 30 * 60_000L),
    )
  }

  @Test
  fun aStateNoNewerThanTheHistoryAddsNothing() {
    val fetched = listOf(point(0, "off"), point(10, "on"))

    assertEquals(fetched, withLiveTail(fetched, state(10, "on")))
    assertEquals(fetched, withLiveTail(fetched, state(5, "on")))
  }

  @Test
  fun anEmptyHistoryStaysEmpty() {
    // An entity the recorder excludes, last updated long before the window: no timeline from it.
    assertEquals(emptyList(), withLiveTail(emptyList(), state(-30 * 24 * 60, "on")))
  }
}
