package ee.schimke.terrazzo.shared

import ee.schimke.ha.model.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PanelViewRouteTest {
  private val views =
    listOf(
      View(title = "Home"),
      View(title = "Kitchen", path = "kitchen"),
      View(title = "Climate", path = "climate-room"),
    )

  @Test
  fun resolveViewIndexMatchesNamedPathAndNumericIndex() {
    assertNull(resolveViewIndex(views, null))
    assertNull(resolveViewIndex(emptyList(), ""))
    assertEquals(0, resolveViewIndex(views, ""))
    assertEquals(1, resolveViewIndex(views, "kitchen"))
    assertEquals(1, resolveViewIndex(views, "KITCHEN"))
    assertEquals(2, resolveViewIndex(views, "climate-room"))
    assertEquals(0, resolveViewIndex(views, "0"))
    assertEquals(2, resolveViewIndex(views, "2"))
    assertNull(resolveViewIndex(views, "3"))
    assertNull(resolveViewIndex(views, "-1"))
    assertNull(resolveViewIndex(views, "unknown"))
  }

  @Test
  fun viewRouteSegmentUsesExplicitPathElseNonZeroIndex() {
    assertEquals("", viewRouteSegment(views, 0))
    assertEquals("kitchen", viewRouteSegment(views, 1))
    assertEquals("climate-room", viewRouteSegment(views, 2))
    assertEquals("1", viewRouteSegment(listOf(View(title = "A"), View(title = "B")), 1))
    assertEquals("", viewRouteSegment(views, 99))
  }
}
