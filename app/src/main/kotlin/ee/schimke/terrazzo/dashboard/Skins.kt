package ee.schimke.terrazzo.dashboard

import android.content.Context
import ee.schimke.ha.client.design.RefreshResult
import ee.schimke.ha.design.DesignEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Installing and removing skins: card designs from the design server, chosen per card by their
 * stable id. Listing a skin costs nothing (the manifest names it); installing one downloads its
 * assets for every theme the dashboard draws in, and only then can a card be switched to it.
 */
object Skins {
  private val LIBRARY_PREFIX = DesignEntry.libraryDesignId("")

  /** The library's skin for a card type: it comes with every sync. */
  fun isLibrary(designId: String): Boolean = designId.startsWith(LIBRARY_PREFIX)

  /** Whether [designId] can be chosen now: a library skin, or one in [installed]. */
  fun isInstalled(designId: String, installed: Set<String>): Boolean =
    isLibrary(designId) || designId in installed

  /** How an [install] ended. */
  enum class Installed {
    /** Its assets are on disk and the skin is installed. */
    Yes,
    /** The server didn't answer, or answered without the skin's assets: nothing was installed. */
    Failed,
  }

  /**
   * Downloads [designId]'s assets for every theme [choice] draws in, then records it installed
   * ([SkinStore]). It runs the design sync's own refresh with the skin added, one refresh at a time
   * with the background sync, and installs the skin only once all of its assets are cached, so an
   * install that fails half way leaves the skin uninstalled. A library skin is installed already.
   */
  suspend fun install(context: Context, choice: CardDesignChoice, designId: String): Installed {
    if (isLibrary(designId)) return Installed.Yes
    if (!choice.enabled) return Installed.Failed
    val withSkin = choice.copy(skins = choice.skins + designId)
    val repository = CardDesignSync.networkRepository(context, withSkin)
    val complete =
      withContext(Dispatchers.IO) {
        CardDesignSync.refreshLock.withLock {
          val result = repository.refresh()
          // Every asset the skin needs, not just some: a refresh that fetched only part of it
          // still reports success.
          result != RefreshResult.Rejected &&
            result != RefreshResult.Failed &&
            repository.isComplete(designId)
        }
      }
    if (!complete) return Installed.Failed
    SkinStore.of(context).add(designId)
    CardDesignSync.stored()
    return Installed.Yes
  }

  /**
   * Removes [designId]: the sync stops fetching it, and a card switched to it draws with its
   * automatic design again until the skin is installed once more. Its cached assets stay on disk
   * (the design cache is never pruned), so installing it again is quick.
   */
  suspend fun uninstall(context: Context, designId: String) {
    if (isLibrary(designId)) return
    SkinStore.of(context).remove(designId)
    CardDesignSync.stored()
  }
}
