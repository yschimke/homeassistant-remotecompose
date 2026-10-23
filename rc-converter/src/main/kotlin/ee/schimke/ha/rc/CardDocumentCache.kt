package ee.schimke.ha.rc

import android.util.Log
import androidx.collection.LruCache
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Process-scoped store of encoded `.rc` byte buffers, keyed by an opaque caller-supplied key.
 *
 * Encoding a card runs the full converter composition + serialization pass. Once the document is
 * captured, swapping its named-binding values (sensor state, mode chip, …) is the player's job —
 * the document bytes don't move. So the cache key must contain only inputs that change the *shape*
 * of the document: the YAML for the card, the theme colours that get baked into paints, the
 * profile, and (for the headless capture path used by widgets) the target pixel size.
 *
 * Snapshot data deliberately is NOT part of the key. A fresh `HaSnapshot` produces the same
 * document bytes; the host pushes new values into the running player by name instead of
 * re-encoding.
 *
 * Lifetime is process-scoped — survives Activity recreation, drops on process death. That matches
 * the immediate goal: a cold-start back-stack pop shouldn't pay for an encode.
 */
interface CardDocumentCache {
  fun get(key: Any): CardDocument?

  fun put(key: Any, document: CardDocument)

  fun clear()

  /**
   * Drops the least recently used documents until at most [maxEntries] remain, for [CardMemory]'s
   * response to memory pressure. A cache with no recency order drops everything.
   */
  fun trimToSize(maxEntries: Int) {
    clear()
  }
}

/**
 * Default in-memory bounded LRU. The size is meant to comfortably hold a multi-view dashboard's
 * worth of cards times the (style, dark) theme variants the user might toggle through; if eviction
 * starts firing, that's a signal to either raise [maxEntries] or shrink the cache key (e.g. drop a
 * dimension that doesn't actually affect the encoded bytes). The eviction warning is emitted at
 * WARN once per eviction so the signal isn't drowned in steady-state noise.
 */
class InMemoryCardDocumentCache(maxEntries: Int = DEFAULT_MAX_ENTRIES) : CardDocumentCache {
  private val cache =
    object : LruCache<Any, CardDocument>(maxEntries) {
      override fun entryRemoved(
        evicted: Boolean,
        key: Any,
        oldValue: CardDocument,
        newValue: CardDocument?,
      ) {
        if (!evicted) return
        // A trim asked for its evictions; only a full cache's is a sizing signal.
        val dropping = dropping
        if (dropping != null) {
          dropping.add(key.hashCode())
        } else {
          Log.w(
            TAG,
            "CardDocumentCache evicted entry; capacity=$maxEntries. " +
              "Either raise maxEntries or tighten the cache key.",
          )
        }
      }
    }

  /** Key hashes the running [trim] drops; null outside one. Written under `this`. */
  private var dropping: IntBuffer? = null

  /**
   * Sorted key hashes the last [trim] dropped, and which of them have been asked for since: the
   * documents that trim cost a re-encode. Hashes rather than keys, so a trim frees the keys too; a
   * collision only miscounts a report. Both guarded by [droppedLock].
   */
  private var dropped: IntArray = EMPTY
  private val reRequested = java.util.BitSet()
  private val droppedLock = Any()
  @Volatile private var anyDropped = false

  override fun get(key: Any): CardDocument? {
    val document = cache.get(key)
    if (document == null && anyDropped) {
      val hash = key.hashCode()
      synchronized(droppedLock) {
        val i = dropped.binarySearch(hash)
        if (i >= 0) reRequested.set(i)
      }
    }
    return document
  }

  override fun put(key: Any, document: CardDocument) {
    cache.put(key, document)
  }

  override fun clear() {
    cache.evictAll()
  }

  override fun trimToSize(maxEntries: Int) {
    trim(maxEntries)
  }

  /**
   * Drops the least recently used documents until at most [maxEntries] remain (none at 0), and
   * reports how this trim and the one before it went: see [DocumentCacheTrim].
   */
  fun trim(maxEntries: Int): DocumentCacheTrim =
    synchronized(this) {
      val sizeBefore = cache.size()
      val hashes = IntBuffer()
      dropping = hashes
      try {
        if (maxEntries <= 0) cache.evictAll() else cache.trimToSize(maxEntries)
      } finally {
        dropping = null
      }
      val now = hashes.sorted()
      synchronized(droppedLock) {
        val report =
          DocumentCacheTrim(
            sizeBefore = sizeBefore,
            kept = cache.size(),
            previousDropped = dropped.size,
            previousReRequested = reRequested.cardinality(),
          )
        dropped = now
        reRequested.clear()
        anyDropped = now.isNotEmpty()
        report
      }
    }

  private companion object {
    const val TAG = "CardDocumentCache"
    const val DEFAULT_MAX_ENTRIES = 256
    val EMPTY = IntArray(0)
  }
}

/**
 * One [InMemoryCardDocumentCache.trim]: how full the cache was and what it kept, and how the
 * previous trim went — how many documents it dropped and how many of those were asked for again
 * before this one. A trim whose dropped documents keep coming back kept too few.
 */
data class DocumentCacheTrim(
  val sizeBefore: Int,
  val kept: Int,
  val previousDropped: Int,
  val previousReRequested: Int,
) {
  val dropped: Int
    get() = sizeBefore - kept
}

/** A growable `IntArray`, so collecting a trim's hashes boxes nothing. */
private class IntBuffer {
  private var values = IntArray(16)
  private var size = 0

  fun add(value: Int) {
    if (size == values.size) values = values.copyOf(size * 2)
    values[size++] = value
  }

  fun sorted(): IntArray = values.copyOf(size).also { it.sort() }
}

/**
 * Composition-scoped binding. Tests / previews can override; production uses
 * [defaultCardDocumentCache].
 */
val LocalCardDocumentCache =
  staticCompositionLocalOf<CardDocumentCache> { defaultCardDocumentCache }

/**
 * Singleton instance reachable from non-Compose call sites (the widget-worker headless capture path
 * uses it without a Composition).
 */
val defaultCardDocumentCache: CardDocumentCache by lazy { InMemoryCardDocumentCache() }
