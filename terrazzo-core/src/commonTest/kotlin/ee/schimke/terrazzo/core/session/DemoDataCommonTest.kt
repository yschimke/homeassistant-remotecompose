package ee.schimke.terrazzo.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The demo house on every target: its dashboards and states come from the bundled files, which
 * Android and the JVM read from the classpath and Wasm from generated source.
 */
class DemoDataCommonTest {
  @Test
  fun everyBundledBoardHasItsDashboardAndStates() {
    for (summary in DemoData.dashboards) {
      val dashboard = DemoData.dashboard(summary.urlPath)
      assertEquals(summary.title, dashboard.title)
      assertTrue(dashboard.views.isNotEmpty(), "${summary.title} has no views")
    }
    val snapshot = DemoData.snapshot(nowMs = 0L)
    assertTrue(snapshot.states.size > 100, "only ${snapshot.states.size} demo states")
    assertNotNull(snapshot.states.keys.firstOrNull { it.startsWith("light.") })
  }

  @Test
  fun fixedDecimalsWritesHomeAssistantNotation() {
    assertEquals("21.5", DemoData.fixedDecimals(21.46, 1))
    assertEquals("-0.30", DemoData.fixedDecimals(-0.3, 2))
    assertEquals("0.0", DemoData.fixedDecimals(-0.04, 1))
    assertEquals("1013.25", DemoData.fixedDecimals(1013.249, 2))
  }
}
