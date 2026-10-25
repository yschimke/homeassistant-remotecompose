package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.DashboardSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardListSelectionTest {
  private val climate = DashboardSummary("climate", "Climate")

  @Test
  fun aLegacyFilterCannotInventAnUnsupportedDefault() {
    val raw = DashboardListState.Ready(listOf(climate))
    assertEquals(raw, applyDashboardSelection(raw, setOf(DEFAULT_DASHBOARD_SENTINEL, "climate")))
    assertEquals(raw, applyDashboardSelection(raw, setOf(DEFAULT_DASHBOARD_SENTINEL)))
  }

  @Test
  fun aSupportedDefaultAndSavedSubsetArePreserved() {
    val home = DashboardSummary(null, "Home")
    val raw = DashboardListState.Ready(listOf(home, climate))
    assertEquals(
      DashboardListState.Ready(listOf(home)),
      applyDashboardSelection(raw, setOf(DEFAULT_DASHBOARD_SENTINEL)),
    )
    assertEquals(
      DashboardListState.Ready(listOf(climate)),
      applyDashboardSelection(raw, setOf("climate")),
    )
  }
}
