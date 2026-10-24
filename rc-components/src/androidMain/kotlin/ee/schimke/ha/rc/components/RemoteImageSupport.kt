@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.core.Limits

/**
 * RemoteCompose `1.0.0-alpha14` gates URL- and file-backed image ops behind
 * [Limits.ENABLE_IMAGE_URLS] / [Limits.ENABLE_IMAGE_FILES], both off by default. `BitmapData.read`
 * throws `RuntimeException("URL image not supported …")` while **parsing** any document that
 * carries a URL image — before a `BitmapLoader` ever runs — so it isn't something the loader can
 * rescue. See `androidx.compose.remote.core.operations.BitmapData.read`.
 *
 * This app authors documents with URL images all over (Home Assistant `entity_picture`,
 * media-player thumbnails, anything routed through `RemoteHaImageUrl` /
 * `rememberLocalNamedRemoteBitmap`), so it has to opt in before any document is decoded or played.
 * The flags are process-global mutable statics; flipping them is idempotent and cheap, so
 * [enableRemoteImageUrls] is called from every document decode / player entry point (the
 * `WrapAdaptiveRemoteDocumentPlayer` view wrapper, the `HaEmbeddedPlayer` Compose wrapper,
 * `CardCapture`) as well as each app's process start.
 */
fun enableRemoteImageUrls() {
  Limits.ENABLE_IMAGE_URLS = true
  Limits.ENABLE_IMAGE_FILES = true
}
