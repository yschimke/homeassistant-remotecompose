package ee.schimke.ha.rc

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

/** Default in-memory bounded LRU. */
class InMemoryCardDocumentCache(maxEntries: Int = 64) : CardDocumentCache {
    private val cache = LruCache<Any, CardDocument>(maxEntries)

    override fun get(key: Any): CardDocument? = cache.get(key)

    override fun put(key: Any, document: CardDocument) {
        cache.put(key, document)
    }

    override fun clear() {
        cache.evictAll()
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
