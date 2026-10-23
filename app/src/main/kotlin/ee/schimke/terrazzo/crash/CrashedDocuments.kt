package ee.schimke.terrazzo.crash

import android.content.Context
import android.util.Log
import ee.schimke.terrazzo.shared.RcDisabledDocuments
import java.io.File

/**
 * The player error boundary ([ee.schimke.terrazzo.shared.RcErrorBoundary]) disables a document that
 * throws while it measures, lays out or draws. One that throws while its player composes can't be
 * caught there and takes the app down; this remembers it (the document [RcDisabledDocuments] says
 * was composing) and disables it on the next start, so a bad document crashes the app once, not on
 * every launch.
 */
internal object CrashedDocuments {
  private const val TAG = "RcErrorBoundary"
  private const val MAX_KEPT = 50

  private fun file(context: Context) = File(context.filesDir, "terrazzo/crashed-documents.txt")

  /** Disables the documents earlier runs crashed in, and logs each document disabled from now. */
  fun install(context: Context, report: (Throwable) -> Unit) {
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

  /** From the uncaught exception handler: records the document composing as it crashed, if any. */
  fun recordCrash(context: Context) {
    val key = RcDisabledDocuments.inFlight() ?: return
    runCatching {
      val file = file(context)
      file.parentFile?.mkdirs()
      file.appendText("$key\n")
    }
  }
}
