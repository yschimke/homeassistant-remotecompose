package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.DashboardSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardStartTest {
  private val named =
    listOf(DashboardSummary("climate", "Climate"), DashboardSummary("printing", "Printing"))
  private val withDefault = named + DashboardSummary(null, "Overview")

  @Test
  fun firstLaunchOpensTheDefaultEvenWhenItIsNotFirstInTheList() {
    assertNull(preferredDashboardPath(withDefault, null))
  }

  @Test
  fun aNamedLovelaceDefaultAlsoTakesPriorityOverOtherDashboards() {
    assertEquals(
      "lovelace",
      preferredDashboardPath(named + DashboardSummary("lovelace", "Overview"), null),
    )
  }

  @Test
  fun aRememberedDashboardTakesPriorityOverTheDefault() {
    assertEquals("printing", preferredDashboardPath(withDefault, "printing"))
    assertNull(preferredDashboardPath(withDefault, DEFAULT_DASHBOARD_SENTINEL))
  }

  @Test
  fun anUnsupportedDefaultOrRemovedDashboardFallsBackToTheFirstAvailable() {
    assertEquals("climate", preferredDashboardPath(named, null))
    assertEquals("climate", preferredDashboardPath(named, "removed"))
    assertNull(preferredDashboardPath(withDefault, "removed"))
  }

  @Test
  fun anEmptyListDoesNotEraseTheRememberedPath() {
    assertEquals("climate", preferredDashboardPath(emptyList(), "climate"))
    assertNull(preferredDashboardPath(emptyList(), null))
  }
}
