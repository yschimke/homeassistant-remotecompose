package ee.schimke.terrazzo.core.logs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.di.AppScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persistent log buffer for the debug Logs view. Hidden behind a preference (default off); when
 * enabled the app feeds three event streams here:
 *
 * - **Data updates** — emitted by [recordDashboardSnapshot] when a dashboard fetch returns a
 *   different state for an entity that the *currently rendered* dashboard references. Entities that
 *   aren't on any dashboard never enter the buffer, so a `get_states` snapshot covering thousands
 *   of unused entities doesn't drown the view. Retained for [DATA_UPDATE_RETENTION_MS] (5 minutes)
 *   so the screen can show "what changed recently" without unbounded growth.
 * - **Connection events** — connect / disconnect / error transitions sourced from
 *   [ee.schimke.terrazzo.core.session.HaSession.connectionStatus].
 * - **Local actions** — button taps, toggles, service calls dispatched by the dashboard's action
 *   dispatcher. Captured *before* the network round-trip so a failed send is still visible.
 * - **Crashes** — uncaught exceptions (persisted synchronously so the trace survives the process
 *   kill) and caught coroutine failures fed via [recordCrash]. Unlike the streams above these are
 *   recorded even when the debug-logs preference is off.
 *
 * Persisted to disk via a Proto DataStore so events survive process death. The in-memory ring is
 * the source of truth for reads / the UI flow; writes are mirrored to disk asynchronously on the
 * store's own IO scope so callers never block on the file. On cold start the file is loaded once
 * and pruned against the current clock — old data updates age out automatically.
 *
 * The store is process-singleton: all hooks share the same buffer regardless of which dashboard /
 * session is active. [clear] empties both the in-memory ring and the persisted copy.
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

  /** Pluggable so tests can drive time. Production uses [System.currentTimeMillis]. */
  var clock: () -> Long = { System.currentTimeMillis() }

  /**
   * Suspend until the persisted buffer has been loaded into memory. Tests use this to
   * deterministically read state that was written to disk in a prior run; production callers (the
   * LogsScreen `collectAsState`) just observe [flow] and see entries appear when load finishes.
   */
  suspend fun awaitInitialLoad() {
    loadJob.join()
  }

  private val loadJob: Job = scope.launch {
    val persisted = dataStore.data.first()
    val loaded = persisted.entries.mapNotNull { it.toModel() }
    val now = clock()
    val merged: Boolean
    synchronized(lock) {
      // Merge — do NOT replace. Events recorded while this
      // suspending load is in flight (e.g. the connection
      // observer firing during boot) live in `entries` already;
      // a naive `clear() + addAll(loaded)` would drop them.
      // Loaded entries are older by construction, so prepend
      // and sort to keep the chronological order the UI relies
      // on. `distinct()` guards against a clean restart where
      // an entry could in principle land twice.
      val current = entries.toList()
      entries.clear()
      entries.addAll((loaded + current).distinct().sortedBy { it.timestamp })
      prune(now)
      merged = current.isNotEmpty()
    }
    publish()
    // Anything recorded during the load gap was only in memory;
    // flush so disk reflects the merged ring.
    if (merged) persistAsync()
  }

  fun recordConnection(status: LogConnectionStatus, message: String? = null) {
    addEntry(LogEntry.Connection(timestamp = clock(), status = status, message = message))
  }

  fun recordLocalAction(summary: String, entityId: String? = null) {
    addEntry(LogEntry.LocalAction(timestamp = clock(), summary = summary, entityId = entityId))
  }

  /**
   * Forwards *non-fatal* crashes to an external backend (Crashlytics) when one is configured.
   * terrazzo-core carries no Firebase dependency, so the app installs the bridge here once its
   * reporter is up; until then it's a no-op and only the in-app buffer is written. Fatal crashes
   * are deliberately *not* routed through this: the backend's own uncaught-exception handler
   * already reports those, and forwarding would double-count them.
   */
  var nonFatalSink: (Throwable) -> Unit = {}

  /**
   * Record an exception in the buffer. Unlike the other feeders this is *not* gated on the
   * debug-logs preference — a crash is worth keeping even if the user never opened the Logs screen,
   * so the trace is there the moment they flip the toggle to diagnose.
   *
   * [fatal] marks an uncaught crash that's about to take the process down (the uncaught-exception
   * handler). Those persist *synchronously* via [persistBlocking] because the async IO scope
   * wouldn't survive the imminent kill. Non-fatal reports — a caught coroutine failure routed
   * through [coroutineExceptionHandler] — ride the normal async path and are additionally forwarded
   * to [nonFatalSink].
   */
  fun recordCrash(
    throwable: Throwable,
    thread: Thread = Thread.currentThread(),
    fatal: Boolean = true,
  ) {
    val entry =
      LogEntry.Crash(
        timestamp = clock(),
        threadName = thread.name,
        summary = throwable.summarize(),
        stackTrace = throwable.stackTraceToString(),
        fatal = fatal,
      )
    synchronized(lock) {
      entries.addLast(entry)
      prune(entry.timestamp)
    }
    publish()
    if (fatal) {
      persistBlocking()
    } else {
      persistAsync()
      // Best-effort: a failing backend mustn't mask the original error.
      runCatching { nonFatalSink(throwable) }
    }
  }

  /**
   * Drop-in [CoroutineExceptionHandler] for the app's top-level scopes (e.g. the process-lifecycle
   * wear-sync scope). An exception that escapes a root `launch` lands here and is recorded as a
   * non-fatal crash; the scope is already torn down by the time the handler runs. Adopting this on
   * a background scope keeps a stray failure there from propagating to the thread's default handler
   * and killing the whole UI process — while still capturing the trace in the in-app log.
   */
  val coroutineExceptionHandler: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
      recordCrash(throwable, fatal = false)
    }

  /**
   * Diff [snapshot] against the last recorded snapshot for any entity referenced by [dashboard],
   * emitting one [LogEntry.DataUpdate] per changed entity. First call after [clear] (or for
   * newly-referenced entities) just seeds the baseline silently — without that, opening the app
   * would log every entity once.
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
          updates +=
            LogEntry.DataUpdate(
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
   * Fire a write of the current ring to disk. The snapshot is read *inside* [DataStore.updateData]
   * so its sequential transform mutex orders writes by completion, not by launch — otherwise two
   * concurrent `persistAsync` calls could land on disk in the opposite order from their in-memory
   * ordering, leaving disk state stale. The `runCatching` swallows IO errors — a missed write on
   * the debug surface isn't worth crashing the app.
   */
  private fun persistAsync() {
    scope.launch {
      runCatching {
        dataStore.updateData {
          val snapshot = synchronized(lock) { entries.map { it.toPersisted() } }
          PersistedLogBuffer(entries = snapshot)
        }
      }
    }
  }

  /**
   * Synchronous sibling of [persistAsync] for the fatal-crash path. Blocks the calling thread until
   * the ring is on disk because the uncaught-exception handler runs microseconds before the default
   * handler kills the process — a write dispatched to [scope] would never flush. Time-boxed by
   * [PERSIST_BLOCKING_TIMEOUT_MS] so a wedged DataStore write can't hang the dying process, and
   * wrapped in `runCatching` for the same reason [persistAsync] is: a missed write on the debug
   * surface mustn't mask the original crash.
   */
  private fun persistBlocking() {
    runCatching {
      runBlocking {
        withTimeoutOrNull(PERSIST_BLOCKING_TIMEOUT_MS) {
          dataStore.updateData {
            val snapshot = synchronized(lock) { entries.map { it.toPersisted() } }
            PersistedLogBuffer(entries = snapshot)
          }
        }
      }
    }
  }

  companion object {
    /** Data updates older than this are dropped on every write. */
    const val DATA_UPDATE_RETENTION_MS: Long = 5 * 60 * 1000L

    /** Hard cap so a chatty session can't grow the buffer without bound. */
    const val MAX_ENTRIES: Int = 500

    /** Upper bound on the blocking disk flush in the crash path. */
    const val PERSIST_BLOCKING_TIMEOUT_MS: Long = 1_000L

    private val Context.logStore: DataStore<PersistedLogBuffer> by
      dataStore(
        fileName = "terrazzo_logs.pb",
        serializer = protoBufStoreSerializer(PersistedLogBuffer()),
      )
  }
}

/** Categorised connection lifecycle, decoupled from the session enum. */
enum class LogConnectionStatus {
  Connected,
  Connecting,
  Disconnected,
  Error,
}

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

  /**
   * An exception captured by the crash plumbing. [fatal] distinguishes an uncaught crash that took
   * the process down from a non-fatal report (a coroutine failure that was logged but recovered).
   */
  data class Crash(
    override val timestamp: Long,
    val threadName: String,
    val summary: String,
    val stackTrace: String,
    val fatal: Boolean,
  ) : LogEntry
}

/** `ClassName: message`, or just the class name when there's no message. */
private fun Throwable.summarize(): String {
  val name = this::class.qualifiedName ?: this::class.simpleName ?: "Throwable"
  val msg = message
  return if (msg.isNullOrBlank()) name else "$name: $msg"
}
