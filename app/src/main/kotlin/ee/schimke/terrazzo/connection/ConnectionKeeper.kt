package ee.schimke.terrazzo.connection

import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.core.session.SessionConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of the live HA connection. Holds the socket open
 * while *any* consumer needs it and parks it (a clean disconnect) when
 * none do, so a backgrounded phone stops poking the network — yet a watch
 * that's actively being looked at still gets live data.
 *
 * Demand = the phone UI is foreground **OR** the watch holds a fresh lease
 * (its app / tile is open and heart-beating, surfaced as the wear sync's
 * `streamActive`). Pinned watch widgets with the watch app *closed*
 * deliberately don't pin the live socket up — that would drain the phone
 * to keep a rarely-glanced complication fresh. The periodic snapshot
 * worker (`WearSnapshotWorker`) covers their freshness at low battery cost
 * instead.
 *
 * Ownership lives here, off the Activity, because the old Activity-RESUMED
 * reconnect loop died the moment the phone UI backgrounded — starving the
 * process-scoped wear sync of any session to read from.
 */
class ConnectionKeeper {
    private val sessionState = MutableStateFlow<HaSession?>(null)
    private val foreground = MutableStateFlow(false)

    /** Swap the active session whenever login / demo state changes. */
    fun setSession(session: HaSession?) {
        sessionState.value = session
    }

    /** Report the phone UI's foreground state — one half of the demand signal. */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    /**
     * Begin owning the connection. [wearWantsData] is the wear sync's
     * "the watch is actively engaged" signal (a fresh lease). Call once
     * from Application.onCreate on a process-lifetime scope; the launched
     * collector lives as long as that scope.
     */
    fun start(scope: CoroutineScope, wearWantsData: Flow<Boolean>) {
        scope.launch {
            val keepAlive = combine(foreground, wearWantsData) { fg, wear -> fg || wear }
            combine(sessionState, keepAlive) { session, keep -> session to keep }
                .distinctUntilChanged()
                .collectLatest { (session, keep) ->
                    if (session == null) return@collectLatest
                    if (keep) maintain(session) else park(session)
                }
        }
    }

    /**
     * Keep [session] connected: connect if it isn't already, then idle
     * until the status flips to Failed and retry after a short backoff.
     * Each retry either lands at Connected (we idle again) or back at
     * Failed (the status re-emits and we retry). Cancelled by
     * [collectLatest] when demand drops or the session changes.
     */
    private suspend fun maintain(session: HaSession) {
        while (true) {
            val status = session.connectionStatus.value
            if (
                status != SessionConnectionStatus.Connected &&
                    status != SessionConnectionStatus.Connecting
            ) {
                runCatching { session.connect() }
            }
            session.connectionStatus.first { it == SessionConnectionStatus.Failed }
            delay(RECONNECT_BACKOFF_MS)
        }
    }

    /**
     * Drop the socket when nothing needs it. Only acts on a live (or
     * connecting) socket — a session that never connected has nothing to
     * tear down, so we skip it rather than log a spurious Disconnected.
     */
    private suspend fun park(session: HaSession) {
        val status = session.connectionStatus.value
        if (
            status == SessionConnectionStatus.Connected ||
                status == SessionConnectionStatus.Connecting
        ) {
            runCatching { session.disconnect() }
        }
    }

    private companion object {
        /**
         * Backoff between automatic reconnect attempts while a consumer
         * wants the connection. Short enough to recover from a blip
         * quickly, long enough not to hammer a genuinely-down instance.
         */
        const val RECONNECT_BACKOFF_MS = 5_000L
    }
}
