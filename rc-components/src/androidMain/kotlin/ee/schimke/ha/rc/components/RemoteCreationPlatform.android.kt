@file:Suppress("RestrictedApi", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package ee.schimke.ha.rc.components

import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.state.MutableRemoteImageBitmap
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteImageBitmap
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteNamedCacheKey
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.text.DecimalFormat

internal actual fun namedRemoteBitmap(
  name: String,
  domain: RemoteState.Domain,
  bitmap: ImageBitmap,
): RemoteImageBitmap =
  MutableRemoteImageBitmap(/* constantValueOrNull= */ null, RemoteNamedCacheKey(domain, name)) {
    creationState ->
    creationState.document.addNamedBitmap(domain.prefixed(name), bitmap.asAndroidBitmap())
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
    creationState.document.addNamedBitmapUrlSized(domain.prefixed(name), url, width, height)
  }

internal actual fun RemoteComposeCreationState.supportsOperation(operation: Int): Boolean =
  operation in profile.supportedOperations

internal actual fun RemoteFloat.toPaddedRemoteString(digits: Int): RemoteString =
  toRemoteString(DecimalFormat("0".repeat(digits)))

/**
 * Local replacement for `RemoteComposeWriter.addNamedBitmapUrl(name, url)` that takes explicit
 * dimensions. The upstream method delegates to `addBitmapUrl(url)` which is `addBitmapUrl(url, 1,
 * 1)`; this calls the three-arg `addBitmapUrl(url, w, h)` so the resulting `BitmapData` op carries
 * real dimensions, then names the slot via `setNamedVariable(id, name, IMAGE_TYPE)`.
 * `setNamedVariable` lives on the underlying `RemoteComposeBuffer` (the writer's `getBuffer()`
 * accessor).
 */
fun RemoteComposeWriter.addNamedBitmapUrlSized(
  name: String,
  url: String,
  width: Int,
  height: Int,
): Int {
  val id = addBitmapUrl(url, width, height)
  buffer.setNamedVariable(id, name, NamedVariable.IMAGE_TYPE)
  return id
}

internal actual fun RemoteInt.toPaddedRemoteString(digits: Int): RemoteString =
  toRemoteString(DecimalFormat("0".repeat(digits)))
