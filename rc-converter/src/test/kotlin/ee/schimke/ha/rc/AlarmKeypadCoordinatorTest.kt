package ee.schimke.ha.rc

import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmKeypadCoordinatorTest {

    private val entity = "alarm_control_panel.house"

    private class CapturingDispatcher : HaActionDispatcher {
        val captured = mutableListOf<HaAction>()
        override fun dispatch(action: HaAction) {
            captured.add(action)
        }
    }

    private fun newCoordinator(
        scope: TestScope,
        timeoutMs: Long = 1500L,
    ): Pair<AlarmKeypadCoordinator, CapturingDispatcher> {
        val sink = CapturingDispatcher()
        val coord = AlarmKeypadCoordinator(
            downstream = sink,
            scope = scope,
            idleTimeoutMs = timeoutMs,
        )
        return coord to sink
    }

    private fun expectedCall(service: String, code: String?): HaAction {
        val data = if (code == null) {
            JsonObject(emptyMap())
        } else {
            JsonObject(mapOf("code" to JsonPrimitive(code)))
        }
        return HaAction.CallService(
            domain = "alarm_control_panel",
            service = "alarm_$service",
            entityId = entity,
            serviceData = data,
        )
    }

    @Test
    fun knownLength_flushesImmediatelyOnNthDigit() = runTest {
        val (coord, sink) = newCoordinator(this)

        coord.dispatch(HaAction.AlarmIntent(entity, "arm_away", codeLength = 4))
        coord.dispatch(HaAction.AlarmKey(entity, "1"))
        coord.dispatch(HaAction.AlarmKey(entity, "2"))
        coord.dispatch(HaAction.AlarmKey(entity, "3"))
        runCurrent()
        // 3 of 4 — handlers ran but length not reached and timer not yet fired.
        assertTrue(sink.captured.isEmpty(), "expected no flush before length reached, got ${sink.captured}")

        coord.dispatch(HaAction.AlarmKey(entity, "4"))
        runCurrent()

        assertEquals(listOf(expectedCall("arm_away", "1234")), sink.captured)
    }

    @Test
    fun unknownLength_flushesAfterIdleTimeout() = runTest {
        val (coord, sink) = newCoordinator(this, timeoutMs = 1000L)

        coord.dispatch(HaAction.AlarmIntent(entity, "disarm", codeLength = null))
        coord.dispatch(HaAction.AlarmKey(entity, "9"))
        coord.dispatch(HaAction.AlarmKey(entity, "8"))
        coord.dispatch(HaAction.AlarmKey(entity, "7"))

        advanceTimeBy(500L)
        runCurrent()
        assertTrue(sink.captured.isEmpty(), "should still be waiting before idle timeout")

        advanceTimeBy(600L)
        advanceUntilIdle()

        assertEquals(listOf(expectedCall("disarm", "987")), sink.captured)
    }

    @Test
    fun typingResetsIdleTimer() = runTest {
        val (coord, sink) = newCoordinator(this, timeoutMs = 1000L)

        coord.dispatch(HaAction.AlarmIntent(entity, "arm_home", codeLength = null))
        coord.dispatch(HaAction.AlarmKey(entity, "1"))
        advanceTimeBy(800L)
        runCurrent()
        coord.dispatch(HaAction.AlarmKey(entity, "2"))
        advanceTimeBy(800L)
        runCurrent()
        // Despite 1.6s elapsed, the second key reset the timer so we
        // should still be in-flight.
        assertTrue(sink.captured.isEmpty())

        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals(listOf(expectedCall("arm_home", "12")), sink.captured)
    }

    @Test
    fun codeLengthZero_skipsBufferingAndFiresImmediately() =
        runTest {
            val (coord, sink) = newCoordinator(this)

            coord.dispatch(HaAction.AlarmIntent(entity, "arm_away", codeLength = 0))
            advanceUntilIdle()

            assertEquals(listOf(expectedCall("arm_away", null)), sink.captured)
        }

    @Test
    fun typeFirstThenIntent_flushesImmediately() = runTest {
        val (coord, sink) = newCoordinator(this)

        coord.dispatch(HaAction.AlarmKey(entity, "5"))
        coord.dispatch(HaAction.AlarmKey(entity, "6"))
        coord.dispatch(HaAction.AlarmKey(entity, "7"))
        coord.dispatch(HaAction.AlarmKey(entity, "8"))
        advanceTimeBy(100L)
        runCurrent()
        // No intent yet -> nothing dispatched.
        assertTrue(sink.captured.isEmpty())

        coord.dispatch(HaAction.AlarmIntent(entity, "disarm", codeLength = null))
        advanceUntilIdle()

        assertEquals(listOf(expectedCall("disarm", "5678")), sink.captured)
    }

    @Test
    fun backspace_removesLastDigit() = runTest {
        val (coord, sink) = newCoordinator(this)

        coord.dispatch(HaAction.AlarmIntent(entity, "arm_away", codeLength = 4))
        coord.dispatch(HaAction.AlarmKey(entity, "1"))
        coord.dispatch(HaAction.AlarmKey(entity, "2"))
        coord.dispatch(HaAction.AlarmKey(entity, "3"))
        coord.dispatch(HaAction.AlarmKey(entity, AlarmKeypadCoordinator.BACKSPACE))
        coord.dispatch(HaAction.AlarmKey(entity, "9"))
        coord.dispatch(HaAction.AlarmKey(entity, "8"))
        advanceUntilIdle()
        // Buffer is "1298" — exactly 4, flush.
        assertEquals(listOf(expectedCall("arm_away", "1298")), sink.captured)
    }

    @Test
    fun clear_emptiesBuffer() = runTest {
        val (coord, sink) = newCoordinator(this)

        coord.dispatch(HaAction.AlarmKey(entity, "1"))
        coord.dispatch(HaAction.AlarmKey(entity, "2"))
        coord.dispatch(HaAction.AlarmKey(entity, AlarmKeypadCoordinator.CLEAR))
        coord.dispatch(HaAction.AlarmKey(entity, "3"))
        coord.dispatch(HaAction.AlarmIntent(entity, "disarm", codeLength = 1))
        advanceUntilIdle()

        assertEquals(listOf(expectedCall("disarm", "3")), sink.captured)
    }

    @Test
    fun unrelatedAction_passesThrough() = runTest {
        val (coord, sink) = newCoordinator(this)
        val toggle = HaAction.Toggle("light.kitchen")

        coord.dispatch(toggle)
        advanceUntilIdle()

        assertEquals(listOf<HaAction>(toggle), sink.captured)
    }

    @Test
    fun nonDigitKeyIgnored() = runTest {
        val (coord, sink) = newCoordinator(this)

        coord.dispatch(HaAction.AlarmIntent(entity, "arm_away", codeLength = 2))
        coord.dispatch(HaAction.AlarmKey(entity, "a")) // ignored
        coord.dispatch(HaAction.AlarmKey(entity, "1"))
        coord.dispatch(HaAction.AlarmKey(entity, "2"))
        advanceUntilIdle()

        assertEquals(listOf(expectedCall("arm_away", "12")), sink.captured)
    }

    @Test
    fun perEntityState_independent() = runTest {
        val (coord, sink) = newCoordinator(this)
        val other = "alarm_control_panel.cabin"

        coord.dispatch(HaAction.AlarmIntent(entity, "arm_away", codeLength = 2))
        coord.dispatch(HaAction.AlarmKey(entity, "1"))
        coord.dispatch(HaAction.AlarmIntent(other, "disarm", codeLength = 2))
        coord.dispatch(HaAction.AlarmKey(other, "9"))
        coord.dispatch(HaAction.AlarmKey(other, "9"))
        coord.dispatch(HaAction.AlarmKey(entity, "2"))
        advanceUntilIdle()

        val expectedOther: HaAction = HaAction.CallService(
            domain = "alarm_control_panel",
            service = "alarm_disarm",
            entityId = other,
            serviceData = JsonObject(mapOf("code" to JsonPrimitive("99"))),
        )
        assertEquals(
            listOf(expectedOther, expectedCall("arm_away", "12")),
            sink.captured,
        )
    }
}

