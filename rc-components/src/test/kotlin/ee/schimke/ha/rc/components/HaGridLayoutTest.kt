package ee.schimke.ha.rc.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HaGridLayoutTest {
  @Test
  fun columnsIsTheMostPerRow() {
    // Plenty of room: capped at columns.
    assertEquals(3, HaGridLayout.perRow(widthDp = 900f, minCellDp = 100, columns = 3))
    assertEquals(5, HaGridLayout.perRow(widthDp = 900f, minCellDp = 100, columns = 5))
  }

  @Test
  fun perRowCountsCellsAndGapsThatFit() {
    // 2 × 140 + 8 = 288 fits in 300; 3 × 140 + 16 = 436 doesn't.
    assertEquals(2, HaGridLayout.perRow(widthDp = 300f, minCellDp = 140, columns = 4))
    // Exactly the width of n cells and their gaps still fits n.
    assertEquals(2, HaGridLayout.perRow(widthDp = 288f, minCellDp = 140, columns = 4))
    assertEquals(1, HaGridLayout.perRow(widthDp = 287f, minCellDp = 140, columns = 4))
    // 4 buttons of 80 dp need 344 dp.
    assertEquals(4, HaGridLayout.perRow(widthDp = 344f, minCellDp = 80, columns = 4))
    assertEquals(3, HaGridLayout.perRow(widthDp = 343f, minCellDp = 80, columns = 4))
  }

  @Test
  fun tooNarrowForOneCellIsStillOnePerRow() {
    assertEquals(1, HaGridLayout.perRow(widthDp = 60f, minCellDp = 140, columns = 3))
    assertEquals(1, HaGridLayout.perRow(widthDp = 0f, minCellDp = 140, columns = 3))
  }

  @Test
  fun degenerateInputsStayPositive() {
    assertEquals(1, HaGridLayout.perRow(widthDp = 500f, minCellDp = 100, columns = 0))
    assertEquals(3, HaGridLayout.perRow(widthDp = 10f, minCellDp = 0, columns = 3))
  }

  @Test
  fun widthForIsTheInverseOfPerRow() {
    assertEquals(140, HaGridLayout.widthFor(1, 140))
    assertEquals(288, HaGridLayout.widthFor(2, 140))
    assertEquals(344, HaGridLayout.widthFor(4, 80))
  }

  @Test
  fun planOfTwoSquarePicturesSwitchesBetweenTwoAndOne() {
    val plan = HaGridLayout.plan(cellCount = 2, columns = 2, minCellDp = 140)
    assertEquals(HaGridLayout.Plan(widePerRow = 2, narrowPerRow = 1, thresholdDp = 288), plan)
  }

  @Test
  fun planCapsWideTierAtTheCardCount() {
    // Two cards in a three-column grid share the row rather than leave a third empty.
    val plan = HaGridLayout.plan(cellCount = 2, columns = 3, minCellDp = 100)
    assertEquals(2, plan.widePerRow)
  }

  @Test
  fun planNarrowTierFitsAPhone() {
    // Tiles: three need 436 dp; a 320 dp phone takes two.
    assertEquals(
      HaGridLayout.Plan(widePerRow = 3, narrowPerRow = 2, thresholdDp = 436),
      HaGridLayout.plan(cellCount = 6, columns = 3, minCellDp = 140),
    )
    // Buttons: four need 344 dp; below that, three.
    assertEquals(
      HaGridLayout.Plan(widePerRow = 4, narrowPerRow = 3, thresholdDp = 344),
      HaGridLayout.plan(cellCount = 8, columns = 4, minCellDp = 80),
    )
    // Five tiles: the narrow tier is what fits the phone, not columns - 1.
    assertEquals(2, HaGridLayout.plan(cellCount = 10, columns = 5, minCellDp = 140).narrowPerRow)
  }

  @Test
  fun aWidgetPlanKeepsShrinkingBelowAPhone() {
    // Four 80 dp buttons in a widget: two need 168 dp, so the narrow tier is one per row, and a
    // 160 dp slot gets 160 dp cells rather than three 48 dp ones.
    assertEquals(
      HaGridLayout.Plan(widePerRow = 4, narrowPerRow = 1, thresholdDp = 344),
      HaGridLayout.plan(
        cellCount = 4,
        columns = 4,
        minCellDp = 80,
        narrowReferenceDp = HaGridLayout.FIXED_NARROW_REFERENCE_DP,
      ),
    )
    // Tiles: not even two fit at the floor.
    assertEquals(
      1,
      HaGridLayout.plan(
          cellCount = 6,
          columns = 3,
          minCellDp = 140,
          narrowReferenceDp = HaGridLayout.FIXED_NARROW_REFERENCE_DP,
        )
        .narrowPerRow,
    )
  }

  @Test
  fun aCaptureOfKnownWidthLaysOutWhatFitsThatWidth() {
    // The review's example: four-column buttons at 160 dp are one per row, at 200 dp two.
    assertEquals(
      1,
      HaGridLayout.perRowAt(widthDp = 160f, cellCount = 4, columns = 4, minCellDp = 80),
    )
    assertEquals(
      2,
      HaGridLayout.perRowAt(widthDp = 200f, cellCount = 4, columns = 4, minCellDp = 80),
    )
    assertEquals(
      4,
      HaGridLayout.perRowAt(widthDp = 344f, cellCount = 4, columns = 4, minCellDp = 80),
    )
    // Tiles at 200 dp: one.
    assertEquals(
      1,
      HaGridLayout.perRowAt(widthDp = 200f, cellCount = 6, columns = 3, minCellDp = 140),
    )
    // Never more per row than there are cards.
    assertEquals(
      2,
      HaGridLayout.perRowAt(widthDp = 900f, cellCount = 2, columns = 4, minCellDp = 80),
    )
  }

  @Test
  fun noTierCellIsNarrowerThanItsMinimumFromTheFloorUp() {
    val floors = listOf(HaGridLayout.NARROW_REFERENCE_DP, HaGridLayout.FIXED_NARROW_REFERENCE_DP)
    for (floor in floors) for (minCell in listOf(60, 80, 100, 120, 140, 160, 240)) {
      for (columns in 1..6) {
        val plan = HaGridLayout.plan(cellCount = 12, columns, minCell, narrowReferenceDp = floor)
        // From the floor (or one cell's minimum, if wider) up, whichever tier plays fits.
        for (width in maxOf(floor, minCell)..1200) {
          val threshold = plan.thresholdDp
          val perRow =
            if (threshold == null || width >= threshold) plan.widePerRow else plan.narrowPerRow
          val cell = (width - HaGridLayout.GAP_DP * (perRow - 1)) / perRow.toFloat()
          assertTrue(
            cell >= minCell,
            "floor $floor, min $minCell, columns $columns: $perRow per row at $width dp " +
              "gives $cell dp cells",
          )
        }
      }
    }
  }

  @Test
  fun singleColumnPlanHasNoGate() {
    assertNull(HaGridLayout.plan(cellCount = 4, columns = 1, minCellDp = 140).thresholdDp)
    assertNull(HaGridLayout.plan(cellCount = 1, columns = 3, minCellDp = 140).thresholdDp)
    assertNull(HaGridLayout.plan(cellCount = 0, columns = 3, minCellDp = 140).thresholdDp)
  }

  @Test
  fun aspectRatiosInEveryFormHaAccepts() {
    assertEquals(1f, parseHaAspectRatio("1:1"))
    assertEquals(16f / 9f, parseHaAspectRatio("16:9"))
    assertEquals(4f / 3f, parseHaAspectRatio("4x3"))
    assertEquals(4f / 3f, parseHaAspectRatio("4X3"))
    assertEquals(16f / 9f, parseHaAspectRatio(" 16 : 9 "))
    assertEquals(1.78f, parseHaAspectRatio("1.78"))
    // A percentage is the height as a share of the width.
    assertEquals(2f, parseHaAspectRatio("50%"))
    assertEquals(1f, parseHaAspectRatio("100%"))
  }

  @Test
  fun malformedAspectRatiosAreNull() {
    assertNull(parseHaAspectRatio(null))
    assertNull(parseHaAspectRatio(""))
    assertNull(parseHaAspectRatio("wide"))
    assertNull(parseHaAspectRatio("16:0"))
    assertNull(parseHaAspectRatio("0:9"))
    assertNull(parseHaAspectRatio("-1"))
    assertNull(parseHaAspectRatio("0%"))
    assertNull(parseHaAspectRatio("1:2:3"))
  }
}
