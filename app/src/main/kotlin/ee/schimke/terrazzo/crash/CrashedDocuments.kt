package ee.schimke.terrazzo.crash

import android.content.Context
import android.util.Log
import ee.schimke.terrazzo.shared.RcDisabledDocuments
import ee.schimke.terrazzo.shared.RcSafeMode
import java.io.File

/**
 * The player error boundary ([ee.schimke.terrazzo.shared.RcErrorBoundary]) disables a document that
 * throws while it measures, lays out or draws. One that throws while its player composes can't be
 * caught there and takes the app down; this remembers it (the document [RcDisabledDocuments] says
 * was composing) and disables it on the next start, so a bad document crashes the app once, not on
 * every launch.
 *
 * Any crash also starts the next run in safe mode ([RcSafeMode]): every document is checked on a
 * background thread before it plays, so one the crash can't be traced to is caught there instead. A
 * run that doesn't crash leaves safe mode behind it.
 */
internal object CrashedDocuments {
  private const val TAG = "RcErrorBoundary"
  private const val MAX_KEPT = 50

  private fun file(context: Context) = File(context.filesDir, "terrazzo/crashed-documents.txt")

  private fun safeModeMarker(context: Context) = File(context.filesDir, "terrazzo/safe-mode")

  /**
   * Disables the documents earlier runs crashed in, starts safe mode when the last run crashed, and
   * logs each document disabled from now.
   */
  fun install(context: Context, report: (Throwable) -> Unit) {
    val marker = safeModeMarker(context)
    if (marker.isFile) {
      RcSafeMode.active = true
      // This run only: it marks the next one again if it crashes too.
      marker.delete()
      Log.w(TAG, "the last run crashed: checking every card before it plays")
    }
    runCatching {
        file(context)
          .takeIf { it.isFile }
          ?.readLines()
          ?.filter { it.isNotBlank() }
          ?.takeLast(MAX_KEPT)
          .orEmpty()
      }
      .getOrDefault(emptyList())
      .forEach { RcDisabledDocuments.disable(it, "It crashed the app while it played") }
    RcDisabledDocuments.onDisabled = { key, error ->
      Log.w(TAG, "disabled document $key", error)
      runCatching { report(error) }
    }
  }

  /**
   * From the uncaught exception handler: starts the next run in safe mode, and records the document
   * composing as it crashed, if any.
   */
  fun recordCrash(context: Context) {
    runCatching {
      val marker = safeModeMarker(context)
      marker.parentFile?.mkdirs()
      marker.writeText("")
    }
    val key = RcDisabledDocuments.inFlight() ?: return
    runCatching {
      val file = file(context)
      file.parentFile?.mkdirs()
      file.appendText("$key\n")
    }
  }
}
