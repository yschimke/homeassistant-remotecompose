package ee.schimke.terrazzo.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal interface AppStorageTransport {
  suspend fun read(): Map<String, String>

  /** Returns the acknowledged import or, on conflict, the install's existing values. */
  suspend fun importIfEmpty(values: Map<String, String>): Map<String, String>

  suspend fun write(key: String, value: String?)
}

internal enum class StorageSyncState {
  SAVED,
  SAVING,
  FAILED,
}

/** Page-scoped storage: seed before composition, then serialize and coalesce background writes. */
internal class WasmoAppStorage
private constructor(
  initial: Map<String, String>,
  private val transport: AppStorageTransport,
  private val scope: CoroutineScope,
) : AppStorage {
  override val location = StorageLocation.WASMO
  private val values = initial.toMutableMap()

  private data class Write(val revision: Long, val value: String?)

  private val pending = linkedMapOf<String, Write>()
  private var revision = 0L
  private var worker: Job? = null
  private val state = MutableStateFlow(StorageSyncState.SAVED)
  val syncState: StateFlow<StorageSyncState> = state

  override fun get(key: String): String? = values[key]

  override fun set(key: String, value: String?) {
    if (value == null) values.remove(key) else values[key] = value
    pending[key] = Write(++revision, value)
    state.value = StorageSyncState.SAVING
    if (worker?.isActive == true) return
    worker = scope.launch {
      // Coalesce rapid UI edits. Each revision is attempted once per worker; a failure stays
      // pending and retries on the next write without blocking independent keys or spinning.
      delay(100)
      val attempted = mutableMapOf<String, Long>()
      while (true) {
        val batch = pending.filter { (key, write) -> attempted[key] != write.revision }
        if (batch.isEmpty()) break
        for ((key, write) in batch) {
          attempted[key] = write.revision
          try {
            transport.write(key, write.value)
            if (pending[key]?.revision == write.revision) pending.remove(key)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // Keep the in-memory value and retry after the next user write.
          }
        }
      }
      state.value = if (pending.isEmpty()) StorageSyncState.SAVED else StorageSyncState.FAILED
    }
  }

  companion object {
    suspend fun create(
      transport: AppStorageTransport,
      scope: CoroutineScope,
      browser: AppStorage,
      migrationKeys: Set<String>,
    ): WasmoAppStorage {
      var values = transport.read()
      if (values.isEmpty()) {
        val local = migrationKeys.mapNotNull { key -> browser.get(key)?.let { key to it } }.toMap()
        if (local.isNotEmpty()) {
          values = transport.importIfEmpty(local)
          // Never erase unacknowledged or conflicting local values, or unrelated browser keys.
          for ((key, value) in local) if (values[key] == value && browser.get(key) == value)
            browser.set(key, null)
        }
      }
      return WasmoAppStorage(values, transport, scope)
    }
  }
}
