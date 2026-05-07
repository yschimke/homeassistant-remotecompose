package ee.schimke.terrazzo.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/**
 * Demo mode is now powered by the captured Lovelace dashboards bundled
 * under `terrazzo-core/src/androidMain/resources/dashboards/<slug>/`
 * (scrubbed by `terrazzo-core/scripts/scrub-demo-dashboards.py`). The
 * tests assert the public surface — dashboard list, fallback, drifting
 * values — without pinning specific entity ids, so re-scrubs that move
 * pseudonyms around don't churn the test file.
 */
class DemoDataTest {

    @Test
    fun isDemo_marker() {
        assertTrue(DemoData.isDemo(DemoData.BASE_URL))
        assertFalse(DemoData.isDemo("http://homeassistant.local:8123"))
        assertFalse(DemoData.isDemo(null))
    }

    @Test
    fun dashboards_cover_seven_captured_boards() {
        // The picker shows the seven captured dashboards. The first
        // entry uses urlPath=null so the default-dashboard load still
        // works; the rest carry their slug as urlPath.
        val list = DemoData.dashboards
        assertEquals(7, list.size, "demo mode should expose all 7 captured dashboards")
        assertEquals(null, list.first().urlPath, "first board uses null urlPath as default")
        assertTrue(list.drop(1).all { it.urlPath != null }, "every other board has a urlPath")
    }

    @Test
    fun dashboard_falls_back_to_default_for_unknown_paths() {
        val defaultTitle = DemoData.dashboards.first().title
        assertEquals(defaultTitle, DemoData.dashboard(null).title)
        assertEquals(defaultTitle, DemoData.dashboard("not-a-real-path").title)
    }

    @Test
    fun snapshot_includes_entities_from_every_captured_board() {
        // Every board's lovelace_config references entities that should
        // resolve in the snapshot — otherwise the demo dashboard renders
        // half the cards as "unavailable".
        val ids = DemoData.snapshot(nowMs = 0L).states.keys
        assertTrue(ids.size > 200, "snapshot should aggregate hundreds of entities, was ${ids.size}")
    }

    @Test
    fun snapshot_values_change_per_minute() {
        // Numeric sensors drift on a per-minute cadence. Two snapshots
        // a minute apart should differ in *some* numeric state.
        val early = DemoData.snapshot(nowMs = 0L)
        val later = DemoData.snapshot(nowMs = 60_000L * 5L)
        val differing = early.states.filter { (id, st) ->
            later.states[id]?.state != st.state
        }
        assertTrue(
            differing.isNotEmpty(),
            "expected at least one entity to drift between minute 0 and minute 5",
        )
    }

    @Test
    fun snapshot_within_minute_is_stable() {
        // The drift bucket is `nowMs / 60_000`. Two snapshots inside
        // the same minute must produce identical state strings (so the
        // dashboard re-poll inside the minute doesn't flicker).
        val a = DemoData.snapshot(nowMs = 1_500L)
        val b = DemoData.snapshot(nowMs = 12_000L)
        assertEquals(a.states.size, b.states.size)
        a.states.forEach { (id, st) ->
            assertEquals(st.state, b.states[id]?.state, "entity $id drifted within a minute")
        }
    }

    @Test
    fun temperature_sensors_keep_celsius_unit() {
        // The drift function preserves unit_of_measurement / device_class
        // attributes — the dashboard renders the unit beside the value.
        val temps = DemoData.snapshot(nowMs = 0L).states.values.filter {
            it.attributes["device_class"]?.jsonPrimitive?.content == "temperature"
        }
        assertTrue(temps.isNotEmpty(), "expected at least one temperature sensor in demo data")
        assertNotNull(
            temps.first().attributes["unit_of_measurement"]?.jsonPrimitive?.content,
            "temperature sensor must keep unit_of_measurement",
        )
    }

    @Test
    fun humidity_stays_in_sane_range() {
        // Guardrail: drifted humidity values must remain plausible
        // (0..100%) so the dashboard doesn't show "humidity 142%".
        for (t in 0L until 60L * 60_000L step 60_000L) {
            val humidities = DemoData.snapshot(nowMs = t).states.values.filter {
                it.attributes["device_class"]?.jsonPrimitive?.content == "humidity"
            }.mapNotNull { it.state.toDoubleOrNull() }
            humidities.forEach {
                assertTrue(it in 0.0..100.0, "humidity out of sane range at t=$t: $it")
            }
        }
    }

    @Test
    fun snapshot_does_not_change_dashboard_size() {
        // Drifting changes state strings, never the entity-id set.
        // Pinned widgets cache id → slot mappings; a stable id set
        // means a widget never has to re-discover its target.
        val a = DemoData.snapshot(nowMs = 0L).states.keys
        val b = DemoData.snapshot(nowMs = 60L * 60_000L).states.keys
        assertEquals(a, b, "drift must not add/remove entities")
        assertNotEquals(0, a.size)
    }
}
