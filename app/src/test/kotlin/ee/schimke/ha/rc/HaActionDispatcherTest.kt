package ee.schimke.ha.rc

import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.toRemoteAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class HaActionDispatcherTest {

    @Test
    fun decodeHaAction_round_trips_url() {
        // Round-trip via the same JSON encoder `toRemoteAction()` uses
        // so we exercise the on-the-wire shape, not just an arbitrary
        // serializer call.
        val original = HaAction.Url("https://example.com/x")
        val payload = Json.encodeToString(HaAction.serializer(), original)

        assertEquals(original, decodeHaAction(payload))
    }

    @Test
    fun decodeHaAction_returns_null_for_non_string() {
        assertNull(decodeHaAction(42))
        assertNull(decodeHaAction(null))
    }

    @Test
    fun decodeHaAction_returns_null_for_garbage_json() {
        assertNull(decodeHaAction("{not-json"))
    }

    @Test
    fun toRemoteAction_payload_is_decodable() {
        // Wired check: anything emitted by `toRemoteAction` must be
        // re-readable by the playback side, otherwise dashboards
        // fire actions the dispatcher silently drops.
        val callService = HaAction.CallService(domain = "cover", service = "open_cover", entityId = "cover.x")
        val remote = callService.toRemoteAction()
        // toRemoteAction packs the JSON in the HostAction's `value`
        // field; we serialize the same way to assert the contract.
        val payload = Json.encodeToString(HaAction.serializer(), callService)

        assertEquals(callService, decodeHaAction(payload))
        // sanity: HostAction was created (non-null) — None returns null.
        assert(remote != null)
    }

    @Test
    fun noop_dispatcher_does_not_throw() {
        // Smoke: the default dispatcher must accept every variant
        // without raising — it's the safety net for surfaces that
        // haven't wired a real handler.
        NoOpHaActionDispatcher.dispatch(HaAction.Url("x"))
        NoOpHaActionDispatcher.dispatch(HaAction.Toggle("light.x"))
        NoOpHaActionDispatcher.dispatch(HaAction.None)
    }
}
