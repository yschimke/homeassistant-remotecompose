package ee.schimke.ha.rc

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import ee.schimke.ha.rc.icons.mdi.MdiIcons
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gives memory back when the system asks for it ([ComponentCallbacks2.onTrimMemory]). The card
 * pipeline keeps several process-wide caches purely for speed — encoded card documents, captured
 * design and launcher templates, built icon glyphs — and every one of them can be rebuilt on
 * demand, so none of them should outlive a request to trim.
 *
 * - While the UI is out of view ([ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]) or the device is
 *   running low, the document cache shrinks to [HIDDEN_DOCUMENTS], about a screen's worth: coming
 *   back to the last board still skips its encodes.
 * - Once the process is in the background LRU list ([ComponentCallbacks2.TRIM_MEMORY_BACKGROUND]
 *   and above, or [ComponentCallbacks2.onLowMemory]) everything goes, along with the host's own
 *   caches ([install]'s `release`). A process that keeps less is less likely to be killed, and its
 *   next start is warm rather than cold.
 *
 * Live values are never cached here, so a card rebuilt after a trim still shows current data on its
 * first frame. Coil trims its own memory cache through its own callbacks.
 *
 * Whether [HIDDEN_DOCUMENTS] is enough shows in [onDocumentTrim]: each trim of the document cache
 * reports how full it was, and how many of the previous trim's dropped documents came back.
 */
object CardMemory {
  /** Card documents kept while the UI is hidden: about one phone screen of cards. */
  const val HIDDEN_DOCUMENTS: Int = 32

  private val installed = AtomicBoolean()

  /**
   * Told about each trim of [defaultCardDocumentCache] that dropped something or followed one that
   * did, with this trim's level and the previous trim's (-1 for none): for the host's analytics.
   * Called on the thread that trims, normally the main thread.
   */
  @Volatile
  var onDocumentTrim: ((level: Int, previousLevel: Int, DocumentCacheTrim) -> Unit)? = null

  @Volatile private var previousLevel = -1

  /**
   * Registers [CardMemory]'s callbacks on [context]'s application, once per process. [release]
   * frees the host's own rebuildable caches when everything goes.
   */
  fun install(context: Context, release: () -> Unit = {}) {
    if (!installed.compareAndSet(false, true)) return
    context.applicationContext.registerComponentCallbacks(
      object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
          if (trim(level)) release()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
          releaseAll()
          release()
        }
      }
    )
  }

  /** Trims for [level]; true when it released everything, so the host should too. */
  @Suppress("DEPRECATION") // RUNNING_LOW is only delivered before API 34, where it still applies.
  fun trim(level: Int): Boolean =
    when {
      level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
        trimDocuments(level, keep = 0)
        releaseAll()
        true
      }
      level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
        trimDocuments(level, keep = HIDDEN_DOCUMENTS)
        false
      }
      else -> false
    }

  private fun trimDocuments(level: Int, keep: Int) {
    val cache = defaultCardDocumentCache
    if (cache !is InMemoryCardDocumentCache) {
      cache.trimToSize(keep)
      return
    }
    val trim = cache.trim(keep)
    // The report's previous half is always the last trim's, even one that dropped nothing.
    val previous = previousLevel
    previousLevel = level
    if (trim.dropped > 0 || trim.previousDropped > 0) {
      runCatching { onDocumentTrim?.invoke(level, previous, trim) }
    }
  }

  /** Drops every rebuildable card cache. */
  fun releaseAll() {
    defaultCardDocumentCache.clear()
    CardDesignLibrary.releaseMemory()
    LauncherDesigns.releaseMemory()
    MdiIcons.releaseMemory()
  }
}
