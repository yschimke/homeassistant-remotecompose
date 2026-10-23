package ee.schimke.terrazzo.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcInput
import ee.schimke.composeai.rcplayer.runtime.RcDocumentLinker
import ee.schimke.composeai.rcplayer.runtime.RcLayoutTree
import ee.schimke.composeai.rcplayer.runtime.RcPlayerState
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import kotlin.coroutines.cancellation.CancellationException

/**
 * Safe mode, for the run after a crash: no document plays until it has been checked away from the
 * screen ([check]). While it is [active], each player's error boundary ([RcErrorBoundary]) shows a
 * placeholder and checks its document on a background thread first: one that throws there is
 * disabled with the atomic-symbol warning, and one that passes plays as usual. A host turns it on
 * at start when its last run crashed, so a document that takes the app down doesn't do it again
 * every launch, even when the crash can't be traced to it.
 */
object RcSafeMode {
  /** Whether documents are checked before they play. Set by the host at start. */
  var active: Boolean by mutableStateOf(false)

  private val checked = mutableStateMapOf<String, Boolean>()

  /** Whether the document [key] has passed [check] in this run. Read in composition. */
  fun passed(key: String): Boolean = checked[key] == true

  /** Records that the document [key] passed [check]. */
  fun pass(key: String) {
    checked[key] = true
  }

  /**
   * Runs [bytes] through the player's runtime without drawing it: decodes it, asks what it needs to
   * play, links it, builds its layout tree, and evaluates its first frame (its expressions,
   * animations and layout-compute blocks). Returns what was thrown, or null when it passed. Pure
   * and blocking, so a caller runs it off the main thread.
   */
  fun check(bytes: ByteArray): Throwable? =
    try {
      val document = RcDocumentCodec.decode(bytes)
      document.composeSupportReport(availableCustomComponents = RcInput.configs)
      val linked = RcDocumentLinker.link(document)
      RcLayoutTree.build(linked)
      val time = RcTimeSource.System
      // No named values, no host: events, repaints and effects go nowhere.
      RcPlayerState(document, emptyMap(), {}, {}, {}, time, { null })
        .beginFrame(0f, time.currentTimeMillis())
      null
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      if (t::class.simpleName == "OutOfMemoryError") throw t
      t
    }

  /** Forgets every check and leaves safe mode: tests only. */
  fun clear() {
    active = false
    checked.clear()
  }
}
