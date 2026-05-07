package ee.schimke.terrazzo.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DemoHaSessionTest {

    @Test
    fun baseUrl_uses_shared_demo_marker() = runTest {
        // The marker baseUrl is the signal that widget provider uses
        // to route the render through DemoData.snapshot() — the
        // session and the provider must agree on it.
        assertSame(DemoData.BASE_URL, DemoHaSession().baseUrl)
    }

    @Test
    fun loadDashboard_threads_the_injected_clock() = runTest {
        // The dashboard screen re-polls on refreshIntervalMillis; each
        // call should see a fresh snapshot derived from the current
        // clock, not a cached one from construction time. Drift is on
        // a per-minute cadence so step the clock by 5 minutes.
        var clockNow = 0L
        val session = DemoHaSession(clock = { clockNow })

        val (_, s1) = session.loadDashboard(null)
        clockNow = 60_000L * 5L
        val (_, s2) = session.loadDashboard(null)

        val differing = s1.states.filter { (id, st) -> s2.states[id]?.state != st.state }
        assertNotEquals(
            0,
            differing.size,
            "expected at least one entity to drift between minute 0 and minute 5",
        )
    }

    @Test
    fun listDashboards_matches_DemoData() = runTest {
        assertEquals(DemoData.dashboards, DemoHaSession().listDashboards())
    }

    @Test
    fun refreshIntervalMillis_is_set_so_dashboard_repolls() {
        // DashboardViewScreen's polling loop breaks when this is null,
        // so demo mode MUST advertise a non-null interval. Guard
        // against a future change that accidentally drops it.
        val interval = assertNotNull(DemoHaSession().refreshIntervalMillis)
        assertTrue(interval > 0L, "refresh interval must be positive, was $interval")
    }
}
