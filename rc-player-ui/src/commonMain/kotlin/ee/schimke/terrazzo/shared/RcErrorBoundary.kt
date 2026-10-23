package ee.schimke.terrazzo.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException

/**
 * The documents a player gave up on, keyed by [rcDocumentKey]. A document that throws while it
 * plays is disabled here for the rest of the process: every player of the same bytes shows
 * [RcDisabledWarning] instead of playing it again, so one bad document can't take down a dashboard,
 * a widget preview or a watch face.
 */
object RcDisabledDocuments {
  private val disabled = mutableStateMapOf<String, String>()
  private val composing = ArrayDeque<String>()

  /** Told about each document as it is disabled, to log or report it. */
  var onDisabled: (key: String, error: Throwable) -> Unit = { _, _ -> }

  /** Why the document [key] was disabled, or null while it plays. Read in composition. */
  fun reason(key: String): String? = disabled[key]

  /** Disables the document [key] for [error]; the first failure is the one kept. */
  fun disable(key: String, error: Throwable) {
    if (key in disabled) return
    disabled[key] = error.describe()
    onDisabled(key, error)
  }

  /** Disables the document [key] for [reason], for one a previous run recorded ([inFlight]). */
  fun disable(key: String, reason: String) {
    if (key !in disabled) disabled[key] = reason
  }

  /**
   * The document whose player was composing when this was read, or null. An exception thrown while
   * a player composes can't be caught where it happens; a host that sees one escape (an uncaught
   * exception handler) records this key, and disables it on its next start.
   *
   * Only a player's own composition, as its boundary composes it, is marked. When live values
   * recompose a scope inside the player, Compose restarts that scope alone, without running the
   * boundary, and offers no hook to mark it: a crash there names no document.
   */
  fun inFlight(): String? = composing.lastOrNull()

  internal fun enter(key: String) = composing.addLast(key)

  internal fun exit(key: String) {
    val at = composing.lastIndexOf(key)
    if (at >= 0) composing.removeAt(at)
  }

  /** Forgets every disabled document: tests only. */
  fun clear() {
    disabled.clear()
    composing.clear()
  }

  private fun Throwable.describe(): String =
    listOfNotNull(this::class.simpleName, message?.take(MAX_REASON)).joinToString(": ")

  private const val MAX_REASON = 200
}

/**
 * A fingerprint of a document's bytes: its length, a 64-bit FNV-1a hash and its 32-bit content
 * hash, so two documents that merely share a length and content hash don't share a key.
 */
fun rcDocumentKey(bytes: ByteArray): String {
  var fnv = FNV_OFFSET
  for (b in bytes) fnv = (fnv xor (b.toLong() and 0xff)) * FNV_PRIME
  return "${bytes.size}:${fnv.toULong().toString(16)}:${bytes.contentHashCode().toUInt().toString(16)}"
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
private const val FNV_PRIME = 0x100000001b3L

/**
 * An error boundary around one player of [bytes]. Exceptions the player throws while it measures,
 * lays out or draws, and those its host callbacks throw when handed [content]'s `fail`, disable the
 * document ([RcDisabledDocuments]); from the next frame it shows [RcDisabledWarning] in its place
 * and the rest of the screen carries on.
 *
 * The boundary lays [content] out as it would lay itself out: [modifier] sizes the boundary, and
 * the player is measured with the constraints that leaves. Compose can't catch an exception thrown
 * while the player composes; [RcDisabledDocuments.inFlight] names the document for a host's crash
 * handler instead.
 */
@Composable
fun RcErrorBoundary(
  bytes: ByteArray,
  modifier: Modifier = Modifier,
  content: @Composable (fail: (Throwable) -> Unit) -> Unit,
) {
  RcErrorBoundary(remember(bytes) { rcDocumentKey(bytes) }, modifier, content)
}

/** [RcErrorBoundary] for a document with no bytes of its own (built in-app), named by [key]. */
@Composable
fun RcErrorBoundary(
  key: String,
  modifier: Modifier = Modifier,
  content: @Composable (fail: (Throwable) -> Unit) -> Unit,
) {
  val reason = RcDisabledDocuments.reason(key)
  if (reason != null) {
    RcDisabledWarning(reason, modifier)
    return
  }
  // A cancellation or running out of memory is rethrown here too, for the callers that hand
  // their own failures over.
  val fail: (Throwable) -> Unit =
    remember(key) { { RcDisabledDocuments.disable(key, it.recoverable()) } }
  Layout(
    content = {
      RcDisabledDocuments.enter(key)
      content(fail)
      RcDisabledDocuments.exit(key)
    },
    modifier =
      modifier.drawWithContent {
        try {
          drawContent()
        } catch (t: Throwable) {
          fail(t)
        }
      },
    measurePolicy = remember(fail) { boundaryLayout(fail) },
  )
}

/** Lays the player out as if the boundary weren't there, failing the document on an exception. */
private fun boundaryLayout(fail: (Throwable) -> Unit) = MeasurePolicy { measurables, constraints ->
  try {
    val placeables = measurables.map { it.measure(constraints) }
    val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceAtLeast(constraints.minWidth)
    val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceAtLeast(constraints.minHeight)
    layout(width, height) {
      try {
        placeables.forEach { it.place(0, 0) }
      } catch (t: Throwable) {
        fail(t)
      }
    }
  } catch (t: Throwable) {
    fail(t)
    layout(constraints.minWidth, constraints.minHeight) {}
  }
}

/**
 * [this], unless it is one a boundary must never swallow: a cancellation or running out of memory.
 */
private fun Throwable.recoverable(): Throwable {
  if (this is CancellationException || this::class.simpleName == "OutOfMemoryError") throw this
  return this
}

/**
 * What a player shows for a document it disabled: an atomic-symbol warning, so a broken document
 * reads as the document's fault rather than as missing data.
 */
@Composable
fun RcDisabledWarning(reason: String, modifier: Modifier = Modifier) {
  Row(
    modifier
      .defaultMinSize(minHeight = 48.dp)
      .fillMaxWidth()
      .border(1.dp, WarningColor, RoundedCornerShape(12.dp))
      .padding(horizontal = 12.dp, vertical = 8.dp)
      .semantics { contentDescription = "Card disabled after an error: $reason" },
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(ATOM, style = TextStyle(color = WarningColor, fontSize = 22.sp))
    BasicText(
      "Card disabled after an error",
      style = TextStyle(color = WarningColor, fontSize = 14.sp),
      maxLines = 2,
    )
  }
}

private const val ATOM = "⚛️"
private val WarningColor = Color(0xFFE0A100)
