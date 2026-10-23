package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A name for the named expression this call site registers in the document being captured: [prefix]
 * and a suffix unique in the document. Two call sites in one document never share it (a shared name
 * let alpha010 hand every cell the first one's value), and every capture of the same card names it
 * the same, so a document's bytes are reproducible: a card design's hash changes only when the
 * design does.
 *
 * Inside [ProvideExpressionNames] (every card [ee.schimke.ha.rc] draws, through `RenderChild`) the
 * suffix is the call site's place in the order the document is composed. Outside it, the suffix is
 * the call site's place in the composition.
 */
@Composable
fun rememberExpressionName(prefix: String): String {
  val names = LocalExpressionNames.current
  val key = currentCompositeKeyHashCode
  return remember(prefix, key) {
    prefix + (names?.next()?.toString() ?: key.toULong().toString(16))
  }
}

/** The expressions named so far in one capture, in composition order. */
class ExpressionNames {
  private var count = 0

  internal fun next(): Int = count++
}

private val LocalExpressionNames = staticCompositionLocalOf<ExpressionNames?> { null }

/**
 * [content] with its expressions named by composition order ([rememberExpressionName]), so the
 * names don't depend on how deep a card is drawn: a card drawn by its converter or by a card design
 * wrapping it names them alike. The outermost call counts for the whole document; a nested one (a
 * card inside a stack) keeps counting, so two cards in one document never share a name.
 */
@Composable
fun ProvideExpressionNames(content: @Composable () -> Unit) {
  if (LocalExpressionNames.current != null) {
    content()
    return
  }
  CompositionLocalProvider(LocalExpressionNames provides remember { ExpressionNames() }, content)
}
