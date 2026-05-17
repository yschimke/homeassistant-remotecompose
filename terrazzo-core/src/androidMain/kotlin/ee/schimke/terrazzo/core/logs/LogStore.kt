package ee.schimke.terrazzo.core.logs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Persistent log buffer for the debug Logs view. Hidden behind a
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
 * Persisted to disk via a Proto DataStore so events survive process
 * death. The in-memory ring is the source of truth for reads / the
 * UI flow; writes are mirrored to disk asynchronously on the store's
 * own IO scope so callers never block on the file. On cold start the
 * file is loaded once and pruned against the current clock — old
 * data updates age out automatically.
 *
 * The store is process-singleton: all hooks share the same buffer
 * regardless of which dashboard / session is active. [clear] empties
 * both the in-memory ring and the persisted copy.
 */
@SingleIn(AppScope::class)
@Inject
class LogStore(context: Context) {

    private val dataStore: DataStore<PersistedLogBuffer> = context.logStore
    // Owns disk reads / writes; SupervisorJob so one failure doesn't
    // kill the rest. Lives for the process — LogStore is AppScope so
    // never cancelled.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val entries: ArrayDeque<LogEntry> = ArrayDeque()
    private val lastSeenStates: MutableMap<String, String> = mutableMapOf()
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow.asStateFlow()

    /**
     * Pluggable so tests can drive time. Production uses
     * [System.currentTimeMillis].
     */
    var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Suspend until the persisted buffer has been loaded into memory.
     * Tests use this to deterministically read state that was written
     * to disk in a prior run; production callers (the LogsScreen
     * `collectAsState`) just observe [flow] and see entries appear
     * when load finishes.
     */
    suspend fun awaitInitialLoad() {
        loadJob.join()
    }

    private val loadJob: Job = scope.launch {
        val persisted = dataStore.data.first()
        val loaded = persisted.entries.mapNotNull { it.toModel() }
        val now = clock()
        synchronized(lock) {
            entries.clear()
            entries.addAll(loaded)
            prune(now)
        }
        publish()
    }

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
        if (updates.isNotEmpty()) {
            publish()
            persistAsync()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            lastSeenStates.clear()
        }
        _flow.value = emptyList()
        persistAsync()
    }

    private fun addEntry(entry: LogEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            prune(entry.timestamp)
        }
        publish()
        persistAsync()
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

    /**
     * Fire a write of the current ring to disk. Writes queue inside
     * DataStore (`updateData` is sequential), so a burst of events
     * coalesces naturally without us tracking versions. The
     * `runCatching` swallows IO errors — a missed write on the debug
     * surface isn't worth crashing the app.
     */
    private fun persistAsync() {
        val snapshot = synchronized(lock) { entries.map { it.toPersisted() } }
        scope.launch {
            runCatching {
                dataStore.updateData { PersistedLogBuffer(entries = snapshot) }
            }
        }
    }

    companion object {
        /** Data updates older than this are dropped on every write. */
        const val DATA_UPDATE_RETENTION_MS: Long = 5 * 60 * 1000L

        /** Hard cap so a chatty session can't grow the buffer without bound. */
        const val MAX_ENTRIES: Int = 500

        private val Context.logStore: DataStore<PersistedLogBuffer> by dataStore(
            fileName = "terrazzo_logs.pb",
            serializer = protoBufStoreSerializer(PersistedLogBuffer()),
        )
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
