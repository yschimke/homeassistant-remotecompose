package ee.schimke.ha.rc.cards

import kotlin.test.Test
import kotlin.test.assertEquals

/** A vertical stack on a fixed canvas shows only the children that fit whole. */
class VerticalStackFitTest {
  @Test
  fun keepsTheChildrenThatFitWhole() {
    // 60 + 8 + 60 = 128 fits a 130dp canvas; the third would need 196.
    assertEquals(2, fittingChildren(listOf(60, 60, 60), availableDp = 130))
    assertEquals(3, fittingChildren(listOf(60, 60, 60), availableDp = 196))
  }

  @Test
  fun dropsAChildThatWouldOnlyShowAsASliver() {
    // The second child would start 8dp above the bottom edge: it is left out, not cut off.
    assertEquals(1, fittingChildren(listOf(100, 100), availableDp = 116))
  }

  @Test
  fun alwaysShowsTheFirstChild() {
    assertEquals(1, fittingChildren(listOf(200, 60), availableDp = 112))
  }
}
