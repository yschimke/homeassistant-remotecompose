package ee.schimke.ha.rc

import ee.schimke.ha.rc.components.HaAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Accumulates [HaAction.AlarmKey] presses per entity and fires the
 * corresponding `alarm_control_panel.alarm_*` service on [downstream]
 * once it decides the user has finished entering an attempt.
 *
 * ## Timing model
 *
 * Each keypad key on the alarm panel is its own host action — the .rc
 * document does not buffer anything. This coordinator owns the buffer
 * on the host side, paired with the most recent [HaAction.AlarmIntent]
 * (which the ARM AWAY / ARM HOME / DISARM buttons emit). It dispatches
 * once one of these "complete attempt" conditions fires:
 *
 * - **Known length (codeLength > 0):** flush the moment the buffer
 *   reaches that length.
 * - **No code required (codeLength = 0):** flush the intent
 *   immediately on press.
 * - **Unknown length (codeLength = null):** flush after [idleTimeoutMs]
 *   of no further key presses while an intent is pending.
 * - **Intent after typing:** if the user types digits first then taps
 *   ARM, flush immediately with whatever's in the buffer.
 *
 * Other [HaAction] variants pass through to [downstream] unchanged so
 * this can sit transparently in front of the dashboard's dispatcher.
 */
class AlarmKeypadCoordinator(
    private val downstream: HaActionDispatcher,
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
) : HaActionDispatcher {

    private class State {
        var intent: HaAction.AlarmIntent? = null
        val code: StringBuilder = StringBuilder()
        var flushJob: Job? = null
    }

    private val mutex = Mutex()
    private val states = mutableMapOf<String, State>()

    override fun dispatch(action: HaAction) {
        when (action) {
            is HaAction.AlarmKey -> scope.launch { handleKey(action) }
            is HaAction.AlarmIntent -> scope.launch { handleIntent(action) }
            else -> downstream.dispatch(action)
        }
    }

    private suspend fun handleKey(action: HaAction.AlarmKey) {
        mutex.withLock {
            val st = states.getOrPut(action.entityId) { State() }
            applyKey(st.code, action.key)
            st.flushJob?.cancel()

            val intent = st.intent
            val len = intent?.codeLength
            if (intent != null && len != null && len > 0 && st.code.length >= len) {
                flushLocked(action.entityId)
                return@withLock
            }
            // Schedule idle timeout — only flushes once an intent is
            // also pending; otherwise the buffer just sits waiting for
            // the user to tap ARM/DISARM.
            st.flushJob = scope.launch {
                delay(idleTimeoutMs)
                mutex.withLock {
                    val cur = states[action.entityId] ?: return@withLock
                    if (cur.intent != null) flushLocked(action.entityId)
                }
            }
        }
    }

    private suspend fun handleIntent(action: HaAction.AlarmIntent) {
        mutex.withLock {
            val st = states.getOrPut(action.entityId) { State() }
            st.intent = action
            st.flushJob?.cancel()

            val len = action.codeLength
            when {
                len == 0 -> {
                    flushLocked(action.entityId)
                }
                len != null && len > 0 && st.code.length >= len -> {
                    flushLocked(action.entityId)
                }
                st.code.isNotEmpty() -> {
                    flushLocked(action.entityId)
                }
                else -> {
                    st.flushJob = scope.launch {
                        delay(idleTimeoutMs)
                        mutex.withLock {
                            val cur = states[action.entityId] ?: return@withLock
                            if (cur.intent != null) flushLocked(action.entityId)
                        }
                    }
                }
            }
        }
    }

    private fun flushLocked(entityId: String) {
        val st = states.remove(entityId) ?: return
        st.flushJob?.cancel()
        val intent = st.intent ?: return
        val code = st.code.toString()
        val data = if (code.isEmpty()) {
            JsonObject(emptyMap())
        } else {
            JsonObject(mapOf("code" to JsonPrimitive(code)))
        }
        downstream.dispatch(
            HaAction.CallService(
                domain = "alarm_control_panel",
                service = "alarm_${intent.service}",
                entityId = intent.entityId,
                serviceData = data,
            ),
        )
    }

    private fun applyKey(buf: StringBuilder, key: String) {
        when (key) {
            BACKSPACE -> if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
            CLEAR -> buf.clear()
            else -> if (key.length == 1 && key[0].isDigit()) buf.append(key)
        }
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS: Long = 1_500L

        const val BACKSPACE: String = "backspace"
        const val CLEAR: String = "clear"
    }
}
