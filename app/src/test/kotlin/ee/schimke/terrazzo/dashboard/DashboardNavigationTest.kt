package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.View
import ee.schimke.terrazzo.core.session.DemoData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardNavigationTest {
  @Test
  fun aPathNamesItsDashboardAndView() {
    assertEquals(LovelacePath("dashboard-bambu", "p2s"), LovelacePath.parse("/dashboard-bambu/p2s"))
    assertEquals(LovelacePath("climate", null), LovelacePath.parse("/climate?edit=1#top"))
    assertNull(LovelacePath.parse("https://example.test/x"))
    assertNull(LovelacePath.parse("//example.test/x"))
    assertNull(LovelacePath.parse("/"))
  }

  @Test
  fun lovelaceIsTheDefaultDashboard() {
    val path = LovelacePath.parse("/lovelace/0")!!
    assertNull(path.dashboardUrlPath)
    assertTrue(path.isIn(null))
    assertFalse(path.isIn("climate"))
  }

  @Test
  fun aViewIsFoundByPathThenPosition() {
    val dashboard = Dashboard(views = listOf(View(title = "Home"), View(path = "p2s")))
    assertEquals(0, dashboard.viewIndexOf(null))
    assertEquals(1, dashboard.viewIndexOf("p2s"))
    assertEquals(1, dashboard.viewIndexOf("1"))
    assertNull(dashboard.viewIndexOf("2"))
    assertNull(dashboard.viewIndexOf("a1mini"))
  }

  @Test
  fun theBundledPrintingDashboardsLinksLandOnItsViews() {
    // Its links name the author's dashboard (`/dashboard-bambu/p2s`); the view is still here.
    val dashboard = DemoData.dashboard("3d-printing")
    assertEquals(1, dashboard.viewIndexOf(LovelacePath.parse("/dashboard-bambu/p2s")!!.view))
    assertEquals(2, dashboard.viewIndexOf(LovelacePath.parse("/dashboard-bambu/a1mini")!!.view))
  }

  @Test
  fun relativeLinksOpenOnHomeAssistant() {
    assertEquals(
      "https://ha.example/history?entity_id=light.x",
      resolveAgainstBase("https://ha.example/", "/history?entity_id=light.x"),
    )
    assertNull(resolveAgainstBase("demo://", "/history"))
    assertEquals(
      "https://other.test",
      resolveAgainstBase("https://ha.example", "https://other.test"),
    )
  }
}
