package ee.schimke.terrazzo.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Dashboard-owned loads survive lazy-item disposal. Values and in-flight requests are shared. */
internal class DashboardLoadCache<K, V>(
  private val scope: CoroutineScope,
  private val capacity: Int = 64,
  private val load: suspend (K) -> V,
) {
  private val mutex = Mutex()
  private val entries = LinkedHashMap<K, Deferred<Result<V>>>()

  // Called on the dashboard's UI dispatcher, like get(). A returning lazy item starts at its
  // final height instead of briefly painting a differently sized loading/fallback card.
  @OptIn(ExperimentalCoroutinesApi::class)
  fun peek(key: K): V? =
    entries[key]?.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()?.getOrNull()

  suspend fun get(key: K): V {
    val entry = mutex.withLock {
      entries.remove(key)?.also { entries[key] = it }
        ?: scope
          .async { runCatching { load(key) } }
          .also {
            entries[key] = it
            while (entries.size > capacity) entries.remove(entries.keys.first())
          }
    }
    return try {
      val result = entry.await()
      // Transient network/decode errors may recover on the next visit. Successful absence
      // (e.g. no exact catalog match) remains cached, so scrolling doesn't repeat resolution.
      if (result.isFailure) mutex.withLock { if (entries[key] === entry) entries.remove(key) }
      result.getOrThrow()
    } catch (e: Throwable) {
      // A disposed item's cancelled await must not evict a request owned by the dashboard.
      if (entry.isCancelled) mutex.withLock { if (entries[key] === entry) entries.remove(key) }
      throw e
    }
  }
}
