package ee.schimke.terrazzo.core.logs

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.di.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory log buffer for the debug Logs view. Hidden behind a
 * preference (default off); when enabled the app feeds three event
 * streams here:
 *
 *  - **Data updates** — emitted by [recordDashboardSnapshot] when a
 *    dashboard fetch returns a different state for an entity that the
 *    *currently rendered* dashboard references. Entities that aren't
 *    on any dashboard never enter the buffer, so a `get_states` snapshot
 *    covering thousands of unused entities doesn't drown the view.
 *    Retained for [DATA_UPDATE_RETENTION_MS] (5 minutes) so the screen
 *    can show "what changed recently" without unbounded growth.
 *  - **Connection events** — connect / disconnect / error transitions
 *    sourced from [ee.schimke.terrazzo.core.session.HaSession.connectionStatus].
 *  - **Local actions** — button taps, toggles, service calls dispatched
 *    by the dashboard's action dispatcher. Captured *before* the
 *    network round-trip so a failed send is still visible.
 *
 * The store is process-singleton: all hooks share the same buffer
 * regardless of which dashboard / session is active. A single
 * [clear] empties everything; useful from the screen itself.
 */
@SingleIn(AppScope::class)
@Inject
class LogStore {

    private val lock = Any()
    private val entries: ArrayDeque<LogEntry> = ArrayDeque()
    private val lastSeenStates: MutableMap<String, String> = mutableMapOf()
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow.asStateFlow()

    /**
     * Pluggable so tests can drive time. Production uses
     * [System.currentTimeMillis] via the default constructor.
     */
    var clock: () -> Long = { System.currentTimeMillis() }

    fun recordConnection(status: LogConnectionStatus, message: String? = null) {
        addEntry(
            LogEntry.Connection(
                timestamp = clock(),
                status = status,
                message = message,
            )
        )
    }

    fun recordLocalAction(summary: String, entityId: String? = null) {
        addEntry(
            LogEntry.LocalAction(
                timestamp = clock(),
                summary = summary,
                entityId = entityId,
            )
        )
    }

    /**
     * Diff [snapshot] against the last recorded snapshot for any
     * entity referenced by [dashboard], emitting one
     * [LogEntry.DataUpdate] per changed entity. First call after
     * [clear] (or for newly-referenced entities) just seeds the
     * baseline silently — without that, opening the app would log
     * every entity once.
     */
    fun recordDashboardSnapshot(dashboard: Dashboard, snapshot: HaSnapshot) {
        val refs = referencedEntities(dashboard)
        if (refs.isEmpty()) return
        val now = clock()
        val updates = mutableListOf<LogEntry>()
        synchronized(lock) {
            for (id in refs) {
                val current = snapshot.states[id]?.state ?: continue
                val prior = lastSeenStates[id]
                lastSeenStates[id] = current
                if (prior != null && prior != current) {
                    updates += LogEntry.DataUpdate(
                        timestamp = now,
                        entityId = id,
                        fromState = prior,
                        toState = current,
                    )
                }
            }
            if (updates.isNotEmpty()) {
                entries.addAll(updates)
                prune(now)
            }
        }
        if (updates.isNotEmpty()) publish()
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            lastSeenStates.clear()
        }
        _flow.value = emptyList()
    }

    private fun addEntry(entry: LogEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            prune(entry.timestamp)
        }
        publish()
    }

    private fun prune(now: Long) {
        val cutoff = now - DATA_UPDATE_RETENTION_MS
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (e is LogEntry.DataUpdate && e.timestamp < cutoff) iterator.remove()
        }
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    private fun publish() {
        _flow.value = synchronized(lock) { entries.toList() }
    }

    companion object {
        /** Data updates older than this are dropped on every write. */
        const val DATA_UPDATE_RETENTION_MS: Long = 5 * 60 * 1000L

        /** Hard cap so a chatty session can't grow the buffer without bound. */
        const val MAX_ENTRIES: Int = 500
    }
}

/** Categorised connection lifecycle, decoupled from the session enum. */
enum class LogConnectionStatus { Connected, Connecting, Disconnected, Error }

sealed interface LogEntry {
    val timestamp: Long

    data class DataUpdate(
        override val timestamp: Long,
        val entityId: String,
        val fromState: String,
        val toState: String,
    ) : LogEntry

    data class Connection(
        override val timestamp: Long,
        val status: LogConnectionStatus,
        val message: String? = null,
    ) : LogEntry

    data class LocalAction(
        override val timestamp: Long,
        val summary: String,
        val entityId: String? = null,
    ) : LogEntry
}
