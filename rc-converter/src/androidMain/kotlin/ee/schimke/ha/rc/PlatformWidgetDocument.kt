@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.Operations
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.ColorAttribute
import androidx.compose.remote.core.operations.ColorConstant
import androidx.compose.remote.core.operations.ColorExpression
import androidx.compose.remote.core.operations.ComponentValue
import androidx.compose.remote.core.operations.DataListIds
import androidx.compose.remote.core.operations.FloatConstant
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.core.operations.IntegerExpression
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.remote.core.operations.TextData
import androidx.compose.remote.core.operations.TextFromFloat
import androidx.compose.remote.core.operations.TextLength
import androidx.compose.remote.core.operations.TextLookup
import androidx.compose.remote.core.operations.TextLookupInt
import androidx.compose.remote.core.operations.TextMerge
import androidx.compose.remote.core.operations.layout.CanvasContent
import androidx.compose.remote.core.operations.layout.CanvasOperations
import androidx.compose.remote.core.operations.layout.Component
import androidx.compose.remote.core.operations.layout.Container
import androidx.compose.remote.core.operations.layout.ContainerEnd
import androidx.compose.remote.core.types.IntegerConstant

/**
 * Downlevel only the final platform-bound document, after the AndroidX baking/codec passes.
 *
 * [android16Wire] rewrites the header's wire version to 1.0, the only one Android 16's decoder
 * accepts. Leave it on for [widgetsProfile] documents, and turn it off for
 * [systemThemedWidgetsProfile] ones: the platform reads wire 1.0 as document API level 6, whose
 * operation table has no profiles and no ColorTheme (196), so an API 37 host failed such a document
 * with "Unknown operation encountered 196". At 1.1 it reads API level 7 and picks the widgets
 * table, which has it.
 */
fun platformWidgetDocument(bytes: ByteArray, android16Wire: Boolean = true): ByteArray {
  require(bytes.size >= 13 && bytes[0].toInt() == Operations.HEADER) {
    "Expected an uncompressed RemoteCompose widget header"
  }
  // Android 16's LayoutComponent.inflate drops data operations that were not yet
  // ComponentData in the platform version (notably TextFromFloat and named variables).
  // Keep the original flat operation stream and lift resource definitions/expressions
  // ahead of the layout. Canvas expressions remain local: evaluating an inactive
  // canvas globally can read an unmeasured dimension (NaN). Actions remain local.
  val operations = arrayListOf<Operation>()
  RemoteComposeBuffer.fromInputStream(bytes.inputStream()).inflateFromBuffer(operations)
  val containerStack = ArrayDeque<Boolean>()
  val resources = arrayListOf<Operation>()
  val body = arrayListOf<Operation>()
  val components = operations.filterIsInstance<Component>().map { it.componentId }.toSet()
  val componentValues =
    operations
      .filterIsInstance<ComponentValue>()
      .filter { it.componentId in components }
      .groupBy { it.componentId }
  for (op in operations) {
    val inCanvas = containerStack.lastOrNull() == true
    when {
      op is ComponentValue && op.componentId in componentValues -> Unit
      !inCanvas && op.javaClass in widgetResourceOperations -> resources.add(op)
      else -> body.add(op)
    }
    when (op) {
      is Container ->
        containerStack.addLast(inCanvas || op is CanvasOperations || op is CanvasContent)
      is ContainerEnd -> containerStack.removeLast()
    }
  }
  val buffer = WireBuffer()
  body.first().write(buffer)
  resources.forEach { it.write(buffer) }
  body.drop(1).forEach { op ->
    op.write(buffer)
    // Older LayoutComponentContent retains only text and bitmap data. Values must
    // sit directly on their owning component, not inside its content container.
    if (op is Component) componentValues[op.componentId]?.forEach { it.write(buffer) }
  }
  return buffer.buffer.copyOf(buffer.size).also { compatible ->
    // alpha21 writes 1.1 even for API-7 documents; Android 16 supports wire 1.0.
    // Preserve the tagged major version and all layout/data/profile fields.
    if (android16Wire) compatible.fill(0, fromIndex = 5, toIndex = 9)
  }
}

private val widgetResourceOperations =
  setOf<Class<out Operation>>(
    TextData::class.java,
    TextFromFloat::class.java,
    TextMerge::class.java,
    TextLookup::class.java,
    TextLookupInt::class.java,
    TextLength::class.java,
    FloatConstant::class.java,
    FloatExpression::class.java,
    IntegerConstant::class.java,
    IntegerExpression::class.java,
    ColorConstant::class.java,
    ColorExpression::class.java,
    ColorAttribute::class.java,
    NamedVariable::class.java,
    DataListIds::class.java,
  )
