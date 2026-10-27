package ee.schimke.ha.designserver

import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.designs.CardDesignLibrary
import ee.schimke.ha.designs.JvmCardPreviewRenderer
import ee.schimke.ha.designs.LibraryCaptureProfile
import ee.schimke.ha.rc.CommonCaptureHost
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Publishes edits to the library's card designs as they are saved in UI Builder, with no build or
 * deployment: server-driven UI first (card-design-server.md, "Publishing").
 *
 * Each check ([syncOnce]) reads every migrated card type's library design from UI Builder
 * ([LibrarySources]). One that differs from the source `main` was built from is captured again on
 * this server ([CardDesignLibrary.rebuildCardType]: that card type's designs, the rest as they are)
 * and installed on `main`, then again with its previews redrawn. The designs take seconds, so an
 * app sees the edit at its next manifest check; the previews of the most used card types take
 * minutes, and the browser shows the old ones until then. The scheduled import still copies the
 * edit into the repository as a pull request, and once that merges, CI publishes the same source
 * and this has nothing left to do.
 *
 * A capture runs on one thread of its own (Compose scenes draw on one thread per process), and only
 * replaces the `main` it was derived from: a library CI published meanwhile wins, and the edit is
 * captured again over it at the next check. An edit that fails to capture is logged and not tried
 * again until it changes or `main` does.
 */
class LibraryEditPublisher(
  private val channels: LibraryChannels,
  private val sources: LibrarySources,
  private val rebuild:
    (source: File, target: File, cardType: String, document: JsonObject, previews: Boolean) -> Int =
    ::rebuildOnThisServer,
) {
  private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

  /** The edits already tried against `main` at [attemptedFor], by card type: their hashes. */
  private val attempted = HashMap<String, String>()
  private var attemptedFor: Long? = null

  /** Checks every library design once; returns the card types it published to `main`. */
  suspend fun syncOnce(): List<String> {
    val published = mutableListOf<String>()
    for (cardType in channels.main.current().sources.keys.sorted()) {
      val library = channels.main.current()
      if (attemptedFor != library.manifest.serial) {
        attempted.clear()
        attemptedFor = library.manifest.serial
      }
      // UI Builder unreachable: nothing to compare, try again next time.
      val source = runCatching { sources.current(cardType) }.getOrNull() ?: continue
      if (!source.edited || source.document == library.sources[cardType]) continue
      val hash = Sha256.hex(source.document.toString().encodeToByteArray())
      if (attempted[cardType] == hash) continue
      attempted[cardType] = hash
      runCatching { publish(library, cardType, source.document) }
        .onSuccess { published += cardType }
        .onFailure { System.err.println("library edit to $cardType not published: ${it.message}") }
    }
    return published
  }

  /** Checks every [intervalMs] until [scope] ends. */
  fun start(scope: CoroutineScope, intervalMs: Long): Job = scope.launch {
    while (isActive) {
      delay(intervalMs)
      runCatching { syncOnce() }
        .onSuccess { if (it.isNotEmpty()) println("library edits published to main: $it") }
        .onFailure { System.err.println("library edit check failed: ${it.message}") }
    }
  }

  /**
   * Installs the edit on `main` in two steps: the card type's designs first, with its old previews
   * (seconds, so apps get the edit at once), then the same with its previews redrawn (minutes, for
   * the browser), unless `main` has moved on meanwhile.
   */
  private suspend fun publish(library: DesignLibrary, cardType: String, document: JsonObject) =
    withContext(worker) {
      val designs = install(library, cardType, document, previews = false)
      channels.main
        .current()
        .takeIf { it.manifest.serial == designs.serial }
        ?.let { published ->
          runCatching { install(published, cardType, document, previews = true) }
            .onFailure {
              System.err.println("previews of the $cardType edit not published: ${it.message}")
            }
        }
      designs
    }

  private fun install(
    library: DesignLibrary,
    cardType: String,
    document: JsonObject,
    previews: Boolean,
  ): LibraryChannels.ChannelInfo {
    val target = channels.stagingDir().also { it.delete() }
    try {
      rebuild(library.dir, target, cardType, document, previews)
    } catch (e: Throwable) {
      target.deleteRecursively()
      throw e
    }
    return channels.install(
      LibraryPaths.MAIN,
      target,
      origin = "edit:$cardType",
      expectSerial = library.manifest.serial,
    )
  }

  companion object {
    private val documentJson = Json { prettyPrint = true }

    /** [CardDesignLibrary.rebuildCardType] here, captured and drawn as the library build does. */
    fun rebuildOnThisServer(
      source: File,
      target: File,
      cardType: String,
      document: JsonObject,
      previews: Boolean,
    ): Int =
      CardDesignLibrary.rebuildCardType(
        source,
        target,
        cardType,
        CardDesignDocument.parse(document.toString()),
        documentJson.encodeToString(JsonObject.serializer(), document) + "\n",
        CommonCaptureHost(LibraryCaptureProfile.OPERATIONS),
        if (previews) JvmCardPreviewRenderer() else null,
      )
  }
}
