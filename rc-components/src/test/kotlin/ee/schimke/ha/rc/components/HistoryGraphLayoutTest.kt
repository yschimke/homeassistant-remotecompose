package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which layout the history graph picks: one card-level empty state, or a row per series. */
class HistoryGraphLayoutTest {

  private fun row(name: String, vararg points: Float) =
    HaHistoryGraphRow("sensor.$name", name, "no data".rs, Color.Gray, points.toList())

  private fun data(vararg rows: HaHistoryGraphRow) =
    HaHistoryGraphData(title = null, rangeLabel = "Last 2 days", rows = rows.toList())

  @Test
  fun aRowNeedsTwoSamplesForALine() {
    assertFalse(row("a").hasLine)
    assertFalse(row("a", 1f).hasLine)
    assertTrue(row("a", 1f, 2f).hasLine)
  }

  @Test
  fun everySeriesEmptyShowsOneEmptyState() {
    assertTrue(data(row("a"), row("b", 3f), row("c"), row("d")).allSeriesEmpty)
  }

  @Test
  fun partlyEmptyKeepsTheGraphs() {
    val partly = data(row("a"), row("b", 1f, 2f, 3f), row("c"))
    assertFalse(partly.allSeriesEmpty)
    assertTrue(partly.rows.single { it.hasLine }.name == "b")
  }

  @Test
  fun noEntitiesIsNotTheHistoryEmptyState() {
    assertFalse(data().allSeriesEmpty)
  }
}
