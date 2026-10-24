package ee.schimke.ha.rc

/**
 * Work done once per key: a second caller for a key waits for the first's result, while other keys
 * go ahead. With a [maxKeys] bound, the key added longest ago is forgotten first.
 */
internal class OncePerKey<K, V : Any>(private val maxKeys: Int = Int.MAX_VALUE) {
  class Slot<V : Any> internal constructor() {
    internal val lock = PlatformLock()
    var value: V? = null
  }

  private val lock = PlatformLock()
  private val slots = LinkedHashMap<K, Slot<V>>()

  /** [block] on [key]'s slot, holding that key's lock. */
  fun <R> locked(key: K, block: Slot<V>.() -> R): R {
    val slot = lock.withLock {
      slots.getOrPut(key) {
        if (slots.size >= maxKeys) slots.remove(slots.keys.first())
        Slot()
      }
    }
    return slot.lock.withLock { slot.block() }
  }

  /**
   * [key]'s value, computing it once. A [compute] that throws is tried again by the next caller.
   */
  fun getOrCompute(key: K, compute: () -> V): V =
    locked(key) { value ?: compute().also { value = it } }

  fun clear() {
    lock.withLock { slots.clear() }
  }
}
