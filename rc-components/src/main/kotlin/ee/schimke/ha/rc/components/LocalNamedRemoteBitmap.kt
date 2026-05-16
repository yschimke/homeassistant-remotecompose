@file:Suppress(
  "RestrictedApi",
  "INVISIBLE_REFERENCE",
  "INVISIBLE_MEMBER",
)

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.compose.state.MutableRemoteBitmap
import androidx.compose.remote.creation.compose.state.RemoteBitmap
import androidx.compose.remote.creation.compose.state.RemoteNamedCacheKey
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rememberNamedState
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * Local replacements for `androidx.compose.remote.creation.compose.state.rememberNamedRemoteBitmap`
 * (alpha010) that work around an upstream encoding bug.
 *
 * `RemoteComposeWriter.addNamedBitmapUrl(name, url)` delegates to
 *
 * ```java
 * public int addBitmapUrl(@NonNull String url) {
 *   return addBitmapUrl(url, 1, 1);   // dimensions default to 1×1
 * }
 * ```
 *
 * So every URL-form named bitmap captures a `BitmapData` op with
 * `mImageWidth=1, mImageHeight=1`. The player allocates a 1×1 slot
 * and the fetched image collapses to invisible — the gray
 * picture-entity tile in #264.
 *
 * The local helpers below mirror upstream's contract — same
 * [rememberNamedState] caching, same [RemoteNamedCacheKey],
 * `domain.prefixed(name)` registration — but the URL form swaps
 * `addNamedBitmapUrl(name, url)` for a direct call to
 * `addBitmapUrl(url, width, height)` via [addNamedBitmapUrlSized].
 *
 * `@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")` lets us
 * reach upstream's `internal` symbols ([rememberNamedState],
 * [MutableRemoteBitmap]'s constructor, [RemoteNamedCacheKey]) the
 * same way `@Suppress("RestrictedApi")` reaches the
 * `@RestrictTo(LIBRARY_GROUP)` ones. Both are needed to keep the
 * encoded byte format identical to upstream's, so a future alpha
 * fix is a clean drop-in.
 */

/**
 * Bake an [ImageBitmap] into the document under [name]. Mirrors
 * upstream's bytes-form `rememberNamedRemoteBitmap` — the bytes
 * form doesn't suffer from the 1×1 bug (dimensions come from the
 * `ImageBitmap`). Kept here so picture-entity bitmap registration
 * has one local seam regardless of strategy.
 */
@Composable
fun rememberLocalNamedRemoteBitmap(
  name: String,
  domain: RemoteState.Domain = RemoteState.Domain.User,
  value: () -> ImageBitmap,
): RemoteBitmap =
  rememberNamedState(name, domain) {
    val bitmap = value()
    MutableRemoteBitmap(
      /* constantValueOrNull = */ null,
      RemoteNamedCacheKey(domain, name),
    ) { creationState ->
      creationState.document.addNamedBitmap(domain.prefixed(name), bitmap.asAndroidBitmap())
    }
  }

/**
 * URL-form named bitmap with explicit [width] / [height]. Replaces
 * upstream's `rememberNamedRemoteBitmap(name, url, domain)` — that
 * version routes through `addNamedBitmapUrl(name, url)` and inherits
 * the 1×1 default. This version threads the dimensions through to
 * `addBitmapUrl(url, w, h)` so the slot has real size, then pairs
 * it with `setNamedVariable(id, …, IMAGE_TYPE)` so the slot is still
 * addressable by name.
 *
 * `RemoteBitmapDecoder.checkBounds` treats the stored width / height
 * as a maximum on URL fetches when `CHECK_DATA_SIZE` is on, so pick
 * a size at least as large as the largest image you expect to load.
 * The tile's draw area is a safe default.
 */
@Composable
fun rememberLocalNamedRemoteBitmap(
  name: String,
  url: String,
  width: Int,
  height: Int,
  domain: RemoteState.Domain = RemoteState.Domain.User,
): RemoteBitmap =
  rememberNamedState(name, domain) {
    MutableRemoteBitmap(
      /* constantValueOrNull = */ null,
      RemoteNamedCacheKey(domain, name),
    ) { creationState ->
      creationState.document.addNamedBitmapUrlSized(
        domain.prefixed(name),
        url,
        width,
        height,
      )
    }
  }

/**
 * Local replacement for `RemoteComposeWriter.addNamedBitmapUrl(name,
 * url)` that takes explicit dimensions. The upstream method
 * delegates to `addBitmapUrl(url)` which is `addBitmapUrl(url, 1,
 * 1)`; this calls the three-arg `addBitmapUrl(url, w, h)` so the
 * resulting `BitmapData` op carries real dimensions, then names the
 * slot via `setNamedVariable(id, name, IMAGE_TYPE)`.
 * `setNamedVariable` lives on the underlying `RemoteComposeBuffer`
 * (the writer's `getBuffer()` accessor).
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
