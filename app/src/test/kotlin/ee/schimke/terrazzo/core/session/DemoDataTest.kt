package ee.schimke.terrazzo.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

class DemoDataTest {

    @Test
    fun isDemo_marker() {
        assertTrue(DemoData.isDemo(DemoData.BASE_URL))
        assertFalse(DemoData.isDemo("http://homeassistant.local:8123"))
        assertFalse(DemoData.isDemo(null))
    }

    @Test
    fun dashboards_cover_expected_paths() {
        val paths = DemoData.dashboards.map { it.urlPath }
        assertEquals(listOf(null, "downstairs", "office"), paths)
    }

    @Test
    fun dashboard_falls_back_to_home_for_unknown_paths() {
        // The picker shouldn't be able to send us an unknown path, but
        // the default dashboard is a sensible fallback if it does.
        assertEquals("Home", DemoData.dashboard(null).title)
        assertEquals("Home", DemoData.dashboard("not-a-real-path").title)
        assertEquals("Downstairs", DemoData.dashboard("downstairs").title)
        assertEquals("Office", DemoData.dashboard("office").title)
    }

    @Test
    fun snapshot_contains_entities_referenced_by_demo_cards() {
        // These ids are hard-coded in DemoData.homeCards / downstairsCards /
        // officeCards. If the snapshot drops one, the corresponding card
        // renders as "unavailable" instead of the intended demo state.
        val referenced = setOf(
            "sensor.living_room",
            "sensor.office_temp",
            "sensor.humidity",
            "sensor.power",
            "sensor.laptop_battery",
            "light.kitchen",
            "light.office_lamp",
            "light.hallway",
            "switch.coffee_maker",
            "switch.desk_fan",
            "lock.front_door",
        )
        val ids = DemoData.snapshot(nowMs = 0L).states.keys
        val missing = referenced - ids
        assertTrue(missing.isEmpty(), "snapshot missing entity ids: $missing")
    }

    @Test
    fun snapshot_values_change_over_time() {
        // The whole point of demo mode is animation: re-capturing the
        // snapshot at later times must produce visibly different
        // sensor values, otherwise the notification and widget
        // refresh cadence show nothing moving.
        val early = DemoData.snapshot(nowMs = 0L)
        val later = DemoData.snapshot(nowMs = 30_000L)
        assertNotEquals(
            early.states["sensor.living_room"]?.state,
            later.states["sensor.living_room"]?.state,
            "living-room temperature should drift over 30s of demo time",
        )
    }

    @Test
    fun office_lamp_toggles_on_eight_second_cadence() {
        // Lamp is on for 0..<8000ms, off for 8000..<16000ms, on again
        // at 16000ms — a visible ~8s cadence so a viewer sees it flip.
        assertEquals("on", DemoData.snapshot(nowMs = 0L).states["light.office_lamp"]?.state)
        assertEquals("off", DemoData.snapshot(nowMs = 9_000L).states["light.office_lamp"]?.state)
        assertEquals("on", DemoData.snapshot(nowMs = 17_000L).states["light.office_lamp"]?.state)
    }

    @Test
    fun humidity_stays_in_sane_range() {
        // Guardrail: if the sine-wave amplitude / baseline ever gets
        // perturbed, we don't want humidity showing as "142%". The
        // dashboard renders the attribute-declared unit (`%`) beside
        // the state string as-is, so obviously-wrong values land on
        // screen.
        for (t in 0L until 300_000L step 5_000L) {
            val raw = DemoData.snapshot(nowMs = t).states["sensor.humidity"]?.state
            assertNotNull(raw, "humidity missing at t=$t")
            val v = raw.toInt()
            assertTrue(v in 20..80, "humidity out of sane range at t=$t: $v")
        }
    }

    @Test
    fun living_room_temp_attributes_declare_celsius() {
        // The dashboard's entities card shows the unit_of_measurement
        // next to the state; a silently-dropped attribute is a visible
        // regression.
        val attrs = DemoData.snapshot(nowMs = 0L).states["sensor.living_room"]?.attributes
        assertNotNull(attrs, "living-room sensor missing from demo snapshot")
        assertEquals("°C", attrs["unit_of_measurement"]?.jsonPrimitive?.content)
        assertEquals("temperature", attrs["device_class"]?.jsonPrimitive?.content)
    }
}
