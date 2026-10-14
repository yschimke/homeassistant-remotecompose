@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.Operations
import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.ColorAttribute
import androidx.compose.remote.core.operations.ColorConstant
import androidx.compose.remote.core.operations.ColorExpression
import androidx.compose.remote.core.operations.ComponentValue
import androidx.compose.remote.core.operations.DataListIds
import androidx.compose.remote.core.operations.FloatConstant
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.IntegerExpression
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.remote.core.operations.TextData
import androidx.compose.remote.core.operations.TextFromFloat
import androidx.compose.remote.core.operations.TextLength
import androidx.compose.remote.core.operations.TextLookup
import androidx.compose.remote.core.operations.TextLookupInt
import androidx.compose.remote.core.operations.TextMerge
import androidx.compose.remote.core.operations.layout.CanvasOperations
import androidx.compose.remote.core.operations.layout.Component
import androidx.compose.remote.core.operations.layout.Container
import androidx.compose.remote.core.operations.layout.ContainerEnd
import androidx.compose.remote.core.types.IntegerConstant
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.platform.AndroidxRcPlatformServices
import androidx.compose.remote.creation.profile.Profile

/**
 * Profile used when capturing the `.rc` document that ships to the launcher as a pinned widget (or
 * to the notification system as a `CustomBigContentView`). Both surfaces hand the bytes to
 * `RemoteViews.DrawInstructions`, which is interpreted by the platform's RemoteCompose runtime —
 * that runtime supports a stricter op set than the embedded AndroidX player, so capturing with the
 * widgets profile up-front keeps the bytes within the launcher's vocabulary.
 *
 * Shape mirrors `RcPlatformProfiles.WIDGETS_V7` (document API level 7,
 * `RcProfiles.PROFILE_WIDGETS`) but ORs in `PROFILE_EXPERIMENTAL` so experimental widget ops are
 * admitted at capture time.
 *
 * **`FEATURE_PAINT_MEASURE = 0` (no paint-measure for widgets).** The alpha default is `1`
 * ("measure during paint instead of wrap behaviour"), which makes
 * [androidx.compose.remote.player.view.platform.RemoteComposeView.onMeasure] skip the document's
 * measure pass and only re-measures content lazily during `dispatchDraw`. A widget's slot size is
 * *externally forced* by the launcher (the cell grid), and the host re-uses one player view across
 * resizes — the embedded long-press preview's `WrapAdaptiveRemoteDocumentPlayer`, and the
 * launcher's own `RemoteViews.DrawInstructions` view. With paint-measure on, that re-used view
 * paints blank the moment it's re-measured at a new size (its layout was cached at the first draw
 * and never rebuilt), which is the "widget goes blank once resized" bug. With the feature off the
 * player runs a real measure pass against the (EXACTLY) slot on every size change, so `fillMaxSize`
 * re-fills and content reflows to the new cell — exactly what we want when the size is dictated
 * from outside.
 *
 * Baking the bit requires the writer's `HTag` varargs constructor (the flag lands in the same
 * single `HEADER` op that carries DOC_WIDTH / DOC_HEIGHT / DOC_PROFILES), which means dropping the
 * `writerCallback` — same tradeoff [androidXExperimentalWrap] already takes: it only tracks
 * PendingIntent-style side effects baked by AndroidX's `pendingIntentAction`. Launcher widget taps
 * instead use numeric host actions: the app collects their ids during capture, then attaches the
 * corresponding PendingIntents to `RemoteViews` after the bytes are encoded.
 */
private const val WIDGETS_API_LEVEL = 7
private const val WIDGETS_OPERATIONS = RcProfiles.PROFILE_WIDGETS or RcProfiles.PROFILE_EXPERIMENTAL

private val widgetOperations =
  requireNotNull(Operations.getOperations(WIDGETS_API_LEVEL, WIDGETS_OPERATIONS)).keySet().toSet() -
    setOf(Operations.CORE_TEXT, Operations.TEXT_STYLE)

// AndroidX alpha21 admits CoreText (239) as an experimental V7 widget operation, but the
// Android 16 platform decoder does not implement it. Excluding it makes the writer use
// its supported TextLayout fallback (208), retaining text/color bindings. Keep this
// compatibility path on newer hosts too; system color support is selected independently.

private fun createWidgetsProfile(supportsColorTheme: Boolean): Profile =
  Profile(
    WIDGETS_API_LEVEL,
    WIDGETS_OPERATIONS,
    AndroidxRcPlatformServices(),
    { if (supportsColorTheme) widgetOperations else widgetOperations - Operations.COLOR_THEME },
  ) { creationDisplayInfo, profile, _ ->
    RemoteComposeWriterAndroid(
      profile,
      RemoteComposeWriter.hTag(Header.DOC_WIDTH, creationDisplayInfo.width),
      RemoteComposeWriter.hTag(Header.DOC_HEIGHT, creationDisplayInfo.height),
      RemoteComposeWriter.hTag(Header.DOC_CONTENT_DESCRIPTION, ""),
      RemoteComposeWriter.hTag(Header.DOC_PROFILES, profile.operationsProfiles),
      RemoteComposeWriter.hTag(Header.FEATURE_PAINT_MEASURE, 0),
    )
  }

/** Android 16 widget host profile; excludes ColorTheme (wire op 196). */
val widgetsProfile: Profile = createWidgetsProfile(supportsColorTheme = false)

/** API 37+ widget host profile, including host-resolved ColorTheme values. */
val systemThemedWidgetsProfile: Profile = createWidgetsProfile(supportsColorTheme = true)

/** Downlevel only the final platform-bound document, after the AndroidX baking/codec passes. */
fun platformWidgetDocument(bytes: ByteArray): ByteArray {
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
      is Container -> containerStack.addLast(inCanvas || op is CanvasOperations)
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
    compatible.fill(0, fromIndex = 5, toIndex = 9)
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
