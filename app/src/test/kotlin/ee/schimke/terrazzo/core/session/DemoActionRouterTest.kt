package ee.schimke.terrazzo.core.session

import ee.schimke.ha.model.EntityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class DemoActionRouterTest {

    private fun base(id: String, state: String, attrs: Map<String, JsonPrimitive> = emptyMap()) =
        EntityState(entityId = id, state = state, attributes = JsonObject(attrs))

    @Test
    fun applyToggle_flips_on_off() {
        val router = DemoActionRouter()
        val states = mapOf("light.kitchen" to base("light.kitchen", "off"))

        router.applyToggle("light.kitchen", "off")

        val result = router.snapshotOverrides(states, nowMs = 0L)
        assertEquals("on", result["light.kitchen"]?.state)
    }

    @Test
    fun applyToggle_flips_open_closed() {
        val router = DemoActionRouter()
        val states = mapOf("cover.front" to base("cover.front", "open"))

        router.applyToggle("cover.front", "open")

        assertEquals("closed", router.snapshotOverrides(states, nowMs = 0L)["cover.front"]?.state)
    }

    @Test
    fun applyToggle_ignores_non_binary_state() {
        val router = DemoActionRouter()
        val states = mapOf("sensor.temp" to base("sensor.temp", "21.5"))

        router.applyToggle("sensor.temp", "21.5")

        // Untouched.
        assertEquals("21.5", router.snapshotOverrides(states, nowMs = 0L)["sensor.temp"]?.state)
    }

    @Test
    fun requestCover_open_starts_animation_from_zero() {
        val router = DemoActionRouter()
        val states = mapOf("cover.garage" to base("cover.garage", "closed"))

        router.requestCover("cover.garage", CoverCommand.Open, nowMs = 1_000L, currentPosition = 0)

        // 100 ms after start: ~2% open, motion = opening.
        val mid = router.snapshotOverrides(states, nowMs = 1_100L)["cover.garage"]
        assertEquals("opening", mid?.state)
        val pos = mid?.attributes?.get("current_position")?.jsonPrimitive?.content?.toIntOrNull()
        assertTrue(pos != null && pos in 1..10, "expected partial-open position, got $pos")
    }

    @Test
    fun requestCover_eventually_settles_to_open() {
        val router = DemoActionRouter()
        val states = mapOf("cover.garage" to base("cover.garage", "closed"))

        router.requestCover("cover.garage", CoverCommand.Open, nowMs = 0L, currentPosition = 0)

        // 6 s in — animation runs to completion (~5 s end-to-end).
        val terminal = router.snapshotOverrides(states, nowMs = 6_000L)["cover.garage"]
        assertEquals("open", terminal?.state)
    }

    @Test
    fun requestCover_close_flips_state_when_already_at_target() {
        val router = DemoActionRouter()
        val states = mapOf("cover.garage" to base("cover.garage", "closed"))

        // Already closed, but request close anyway — should at least
        // ensure the override settles `closed` so the label is right.
        router.requestCover("cover.garage", CoverCommand.Close, nowMs = 0L, currentPosition = 0)

        assertEquals("closed", router.snapshotOverrides(states, nowMs = 0L)["cover.garage"]?.state)
    }

    @Test
    fun mutations_bump_epoch() {
        val router = DemoActionRouter()
        val before = router.epoch.value

        router.applyToggle("light.kitchen", "off")
        val afterToggle = router.epoch.value

        router.requestCover("cover.x", CoverCommand.Open, nowMs = 0L, currentPosition = 50)
        val afterCover = router.epoch.value

        assertNotEquals(before, afterToggle)
        assertNotEquals(afterToggle, afterCover)
    }

    @Test
    fun reset_clears_overrides_and_bumps_epoch() {
        val router = DemoActionRouter()
        router.applyToggle("light.kitchen", "off")
        val states = mapOf("light.kitchen" to base("light.kitchen", "off"))

        // Sanity: override took effect.
        assertEquals("on", router.snapshotOverrides(states, nowMs = 0L)["light.kitchen"]?.state)

        val epochBeforeReset = router.epoch.value
        router.reset()

        assertEquals("off", router.snapshotOverrides(states, nowMs = 0L)["light.kitchen"]?.state)
        assertNotEquals(epochBeforeReset, router.epoch.value)
    }

    @Test
    fun demoCoverPositionPercent_reads_attribute() {
        val state = base(
            "cover.x", "open",
            attrs = mapOf("current_position" to JsonPrimitive(73)),
        )
        assertEquals(73, state.demoCoverPositionPercent())
    }

    @Test
    fun demoCoverPositionPercent_falls_back_by_state() {
        assertEquals(100, base("cover.x", "open").demoCoverPositionPercent())
        assertEquals(0, base("cover.x", "closed").demoCoverPositionPercent())
        assertEquals(50, base("cover.x", "opening").demoCoverPositionPercent())
    }
}
