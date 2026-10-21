package ee.schimke.ha.rc

import android.os.Trace

/**
 * Runs [block] inside a system trace section named [name], so a Perfetto trace (and
 * `:app-benchmark`'s `TraceSectionMetric`s, which match these names) shows what a card's
 * main-thread work cost. Section names are `harc:` plus what the block does; keep them in step with
 * the benchmark's.
 */
inline fun <T> harcTrace(name: String, block: () -> T): T {
  Trace.beginSection(name)
  try {
    return block()
  } finally {
    Trace.endSection()
  }
}

/** Trace section names `:app-benchmark` measures. */
object HarcTraceSections {
  /** Resolving a card's design template (the server's or the local library's) for its key. */
  const val CARD_TEMPLATE = "harc:cardTemplate"

  /** Capturing a card's Remote Compose document through its converter (a document cache miss). */
  const val CAPTURE_DOCUMENT = "harc:captureDocument"

  /** Decoding and checking a document for the Compose player (a decoded-document cache miss). */
  const val DECODE_DOCUMENT = "harc:decodeDocument"
}
