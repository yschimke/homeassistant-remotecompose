@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteContentScale
import ee.schimke.composeai.rccreation.compose.layout.RemoteImage
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rememberMutableRemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.rememberNamedRemoteImageBitmap

/**
 * Sample image composables — three flavours, picked by where the bytes live:
 *
 * - [RemoteHaImageInline] — anonymous embed. The bitmap bytes are baked into the document and have
 *   no host-visible name. Use when the image is fixed for the lifetime of this document and there's
 *   no need to update it afterwards (vendor logo, baked icon).
 *
 * - [RemoteHaImageNamed] — embed bytes **with a name**. Bytes still travel inside the document, but
 *   the name lets the host push updates later (e.g. a fresh PNG every time the underlying entity's
 *   state changes). Use for **local images that are available at generation time and updated
 *   later** — the document renders fully offline on first paint, and the name is the channel for
 *   in-place swaps.
 *
 * - [RemoteHaImageUrl] — embed only a URL. The player's `BitmapLoader` resolves it at playback. Use
 *   for **URLs we don't own and can't bake** (HA `entity_picture`, media-player thumbnails,
 *   anything served by an external host). The same `.rc` survives image swaps and the bytes stay in
 *   the host's image cache (e.g. Coil's disk cache) instead of every snapshot. Pair with
 *   `CoilBitmapLoader` from `rc-image-coil` for HTTP / disk-cached resolution.
 *
 *   Hosts that don't wire a `BitmapLoader` get RemoteCompose's default, which calls
 *   `URL.openStream()` synchronously. That blows up in offline / preview environments with
 *   `UnknownHostException` — so any embedding surface that uses [RemoteHaImageUrl] should pass an
 *   explicit loader.
 */

/** Anonymous bitmap baked into the document. */
@Composable
@RemoteComposable
fun RemoteHaImageInline(
  bitmap: ImageBitmap,
  contentDescription: RemoteString,
  modifier: RemoteModifier = RemoteModifier,
  contentScale: RemoteContentScale = RemoteContentScale.Fit,
) {
  val rb = rememberMutableRemoteImageBitmap(bitmap)
  RemoteImage(
    bitmap = rb,
    contentDescription = contentDescription,
    modifier = modifier,
    contentScale = contentScale,
  )
}

/**
 * Bitmap baked into the document under [name]. Renders correctly with no `BitmapLoader` wiring (the
 * bytes are inline); the [name] is the channel for state-driven updates.
 */
@Composable
@RemoteComposable
fun RemoteHaImageNamed(
  name: String,
  bitmap: ImageBitmap,
  contentDescription: RemoteString,
  modifier: RemoteModifier = RemoteModifier,
  contentScale: RemoteContentScale = RemoteContentScale.Fit,
  domain: RemoteState.Domain = RemoteState.Domain.User,
) {
  val rb = rememberNamedRemoteImageBitmap(name = name, domain = domain, value = { bitmap })
  RemoteImage(
    bitmap = rb,
    contentDescription = contentDescription,
    modifier = modifier,
    contentScale = contentScale,
  )
}

/**
 * URL-only reference. The player calls `BitmapLoader.loadBitmap(url)` at playback. [url] is passed
 * verbatim to the loader, so anything Coil (or the host's chosen loader) can fetch works:
 * `http(s)://…`, `file://…`, `content://…`, `android.resource://…`, an absolute file path, etc.
 *
 * Embedding surfaces **must** wire a loader (see `CoilBitmapLoader` in `rc-image-coil`); the
 * default loader does a blocking `URL.openStream()` and crashes in offline / preview environments.
 */
@Composable
@RemoteComposable
fun RemoteHaImageUrl(
  url: String,
  contentDescription: RemoteString,
  modifier: RemoteModifier = RemoteModifier,
  contentScale: RemoteContentScale = RemoteContentScale.Fit,
  name: String = url,
  domain: RemoteState.Domain = RemoteState.Domain.User,
) {
  val rb = rememberNamedRemoteImageBitmap(name = name, url = url, domain = domain)
  RemoteImage(
    bitmap = rb,
    contentDescription = contentDescription,
    modifier = modifier,
    contentScale = contentScale,
  )
}
