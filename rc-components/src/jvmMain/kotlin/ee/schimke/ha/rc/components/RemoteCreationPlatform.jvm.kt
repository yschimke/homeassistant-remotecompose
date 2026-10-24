@file:Suppress("RestrictedApi", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.common.NamedVariableType
import androidx.compose.remote.creation.common.RemoteDocumentWriter
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationContext
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.state.DecimalFormat
import androidx.compose.remote.creation.compose.state.MutableRemoteImageBitmap
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteImageBitmap
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteNamedCacheKey
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun namedRemoteBitmap(
  name: String,
  domain: RemoteState.Domain,
  bitmap: ImageBitmap,
): RemoteImageBitmap {
  val png =
    requireNotNull(Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG))
      .bytes
  return MutableRemoteImageBitmap(
    /* constantValueOrNull= */ null,
    RemoteNamedCacheKey(domain, name),
  ) { creationState ->
    creationState
      .documentWriter()
      .addNamedBitmapPng(domain.prefixed(name), png, bitmap.width, bitmap.height)
  }
}

internal actual fun namedRemoteBitmapUrl(
  name: String,
  domain: RemoteState.Domain,
  url: String,
  width: Int,
  height: Int,
): RemoteImageBitmap =
  MutableRemoteImageBitmap(/* constantValueOrNull= */ null, RemoteNamedCacheKey(domain, name)) {
    creationState ->
    val id = creationState.writer.addBitmapUrl(url, width, height)
    creationState.writer.setNamedVariable(id, domain.prefixed(name), NamedVariableType.IMAGE)
    id
  }

internal actual fun RemoteComposeCreationState.supportsOperation(operation: Int): Boolean =
  operation in supportedOperations

actual fun RemoteFloat.toPaddedRemoteString(digits: Int): RemoteString =
  toRemoteString(paddedFormat(digits))

actual fun RemoteInt.toPaddedRemoteString(digits: Int): RemoteString =
  toRemoteString(paddedFormat(digits))

/** `java.text.DecimalFormat("0".repeat(digits))`'s settings, in the fork's own format. */
private fun paddedFormat(digits: Int): DecimalFormat =
  DecimalFormat().apply {
    minimumIntegerDigits = digits
    maximumFractionDigits = 0
    isGroupingUsed = false
  }

private fun RemoteComposeCreationContext.documentWriter(): RemoteDocumentWriter =
  writer as? RemoteDocumentWriter
    ?: error("Named bitmaps need a document writer, not ${writer::class.simpleName}")
