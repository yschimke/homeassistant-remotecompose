package ee.schimke.ha.rc.cards

import kotlin.test.Test
import kotlin.test.assertEquals

/** A grid in a Wear slot: one row up to three cells, then two rows, never four to a row. */
class WearGridPerRowTest {
  @Test
  fun aWearGridKeepsAtMostThreeCellsToARow() {
    assertEquals(
      listOf(1, 1, 2, 3, 2, 3, 3, 3, 3),
      (0..8).map { wearGridPerRow(it) },
    )
    assertEquals(6, WEAR_GRID_MAX_CELLS)
  }
}
