package ee.schimke.ha.rc

import android.util.Log
import androidx.collection.LruCache
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Process-scoped store of encoded `.rc` byte buffers, keyed by an
 * opaque caller-supplied key.
 *
 * Encoding a card runs the full converter composition + serialization
 * pass. Once the document is captured, swapping its named-binding
 * values (sensor state, mode chip, …) is the player's job — the
 * document bytes don't move. So the cache key must contain only inputs
 * that change the *shape* of the document: the YAML for the card, the
 * theme colours that get baked into paints, the profile, and (for the
 * headless capture path used by widgets) the target pixel size.
 *
 * Snapshot data deliberately is NOT part of the key. A fresh
 * `HaSnapshot` produces the same document bytes; the host pushes new
 * values into the running player by name instead of re-encoding.
 *
 * Lifetime is process-scoped — survives Activity recreation, drops on
 * process death. That matches the immediate goal: a cold-start
 * back-stack pop shouldn't pay for an encode.
 */
interface CardDocumentCache {
    fun get(key: Any): CardDocument?
    fun put(key: Any, document: CardDocument)
    fun clear()
}

/**
 * Default in-memory bounded LRU. The size is meant to comfortably hold
 * a multi-view dashboard's worth of cards times the (style, dark)
 * theme variants the user might toggle through; if eviction starts
 * firing, that's a signal to either raise [maxEntries] or shrink the
 * cache key (e.g. drop a dimension that doesn't actually affect the
 * encoded bytes). The eviction warning is emitted at WARN once per
 * eviction so the signal isn't drowned in steady-state noise.
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
                if (evicted) {
                    Log.w(
                        TAG,
                        "CardDocumentCache evicted entry; capacity=$maxEntries. " +
                            "Either raise maxEntries or tighten the cache key.",
                    )
                }
            }
        }

    override fun get(key: Any): CardDocument? = cache.get(key)

    override fun put(key: Any, document: CardDocument) {
        cache.put(key, document)
    }

    override fun clear() {
        cache.evictAll()
    }

    private companion object {
        const val TAG = "CardDocumentCache"
        const val DEFAULT_MAX_ENTRIES = 256
    }
}

/**
 * Composition-scoped binding. Tests / previews can override; production
 * uses [defaultCardDocumentCache].
 */
val LocalCardDocumentCache = staticCompositionLocalOf<CardDocumentCache> { defaultCardDocumentCache }

/**
 * Singleton instance reachable from non-Compose call sites (the
 * widget-worker headless capture path uses it without a Composition).
 */
val defaultCardDocumentCache: CardDocumentCache by lazy { InMemoryCardDocumentCache() }
