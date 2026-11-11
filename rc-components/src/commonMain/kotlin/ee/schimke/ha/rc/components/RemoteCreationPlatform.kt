@file:Suppress("RestrictedApi", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.common.NamedVariable
import ee.schimke.composeai.rccreation.common.RemoteDocumentWriter
import ee.schimke.composeai.rccreation.compose.capture.RemoteComposeCaptureState
import ee.schimke.composeai.rccreation.compose.capture.RemoteComposeCreationState
import ee.schimke.composeai.rccreation.compose.state.DecimalFormat
import ee.schimke.composeai.rccreation.compose.state.MutableRemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.RemoteNamedCacheKey
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.RemoteInt
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The writer calls the creation API has no composable for. Cards are written against rc-players'
 * Compose Multiplatform creation API on every target, so these are one implementation; what is
 * left per platform is encoding a bitmap ([encodeBitmapPng]).
 */

/** [bitmap] as PNG bytes: the platform's own encoder (Android's `Bitmap`, Skia elsewhere). */
internal expect fun encodeBitmapPng(bitmap: ImageBitmap): ByteArray

/** A bitmap named [name] in [domain], baked into the document from [bitmap]. */
internal fun namedRemoteBitmap(
  name: String,
  domain: RemoteState.Domain,
  bitmap: ImageBitmap,
): RemoteImageBitmap {
  val png by lazy { encodeBitmapPng(bitmap) }
  return MutableRemoteImageBitmap(
    /* constantValueOrNull= */ null,
    RemoteNamedCacheKey(domain, name),
  ) { creationState ->
    creationState
      .documentWriter()
      .addNamedBitmapPng(domain.prefixed(name), png, bitmap.width, bitmap.height)
  }
}

/** A URL bitmap named [name] in [domain], with real [width] and [height]. */
internal fun namedRemoteBitmapUrl(
  name: String,
  domain: RemoteState.Domain,
  url: String,
  width: Int,
  height: Int,
): RemoteImageBitmap =
  MutableRemoteImageBitmap(/* constantValueOrNull= */ null, RemoteNamedCacheKey(domain, name)) {
    creationState ->
    val id = creationState.document.addBitmapUrl(url, width, height)
    creationState.document.setNamedVariable(id, domain.prefixed(name), NamedVariable.IMAGE_TYPE)
    id
  }

/** Whether the document's capture supports [operation], a Remote Compose opcode. */
internal fun RemoteComposeCreationState.supportsOperation(operation: Int): Boolean =
  (this as? RemoteComposeCaptureState)?.supportedOperations?.contains(operation) == true

/**
 * [this] as a whole number with at least [digits] digits, zero-padded (`DecimalFormat("00")` for
 * two).
 */
fun RemoteFloat.toPaddedRemoteString(digits: Int): RemoteString =
  toRemoteString(paddedFormat(digits))

/** [RemoteFloat.toPaddedRemoteString] for an integer. */
fun RemoteInt.toPaddedRemoteString(digits: Int): RemoteString = toRemoteString(paddedFormat(digits))

/**
 * [this] with up to [maxFractionDigits] decimals and no grouping (`DecimalFormat("0.#")` for one):
 * `21`, `21.5`.
 */
fun RemoteFloat.toDecimalRemoteString(maxFractionDigits: Int): RemoteString =
  toRemoteString(
    DecimalFormat().apply {
      maximumFractionDigits = maxFractionDigits
      isGroupingUsed = false
    },
  )

/** `java.text.DecimalFormat("0".repeat(digits))`'s settings, in the creation API's own format. */
private fun paddedFormat(digits: Int): DecimalFormat =
  DecimalFormat().apply {
    minimumIntegerDigits = digits
    maximumFractionDigits = 0
    isGroupingUsed = false
  }

private fun RemoteComposeCreationState.documentWriter(): RemoteDocumentWriter =
  document as? RemoteDocumentWriter
    ?: error("Named bitmaps need a document writer, not ${document::class.simpleName}")

/** Remote Compose protocol values the player defines, which neither creation library exports. */
internal object RcProtocol {
  private fun asNan(id: Int): Float = Float.fromBits(id or -0x800000)

  /** The host's time: seconds, minutes and hours, as the player's system float variables. */
  val FLOAT_TIME_IN_SEC: Float = asNan(2)
  val FLOAT_TIME_IN_MIN: Float = asNan(3)
  val FLOAT_TIME_IN_HR: Float = asNan(4)
}
