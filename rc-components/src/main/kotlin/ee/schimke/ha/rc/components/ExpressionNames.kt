package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.remember

/**
 * A name for the named expression this call site registers in the document being captured: [prefix]
 * and the call site's place in the composition. Two call sites in one document never share it (a
 * shared name let alpha010 hand every cell the first one's value), and every capture of the same
 * card names it the same, so a document's bytes are reproducible: a card design's hash changes only
 * when the design does.
 */
@Composable
fun rememberExpressionName(prefix: String): String {
  val key = currentCompositeKeyHashCode
  return remember(prefix, key) { prefix + key.toULong().toString(16) }
}
