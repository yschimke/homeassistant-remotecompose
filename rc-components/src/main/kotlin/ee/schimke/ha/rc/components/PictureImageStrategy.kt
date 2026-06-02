@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * How `picture-entity` (and other URL-bitmap cards) emit their image into the captured `.rc`
 * document.
 *
 * Two production modes:
 *
 * - [Url] — emit the URL form via `rememberLocalNamedRemoteBitmap(name, url, w, h)`. The player
 *   resolves the URL via the configured `BitmapLoader` at playback. Use in the **app dashboard**
 *   where a Coil-backed loader is wired.
 *
 * - [Inline] — emit the bytes form via `rememberLocalNamedRemoteBitmap(name) { bitmap }` with the
 *   bitmap baked into the doc. Use in **widgets**, where the runtime has no `BitmapLoader` and the
 *   doc must self-contain every pixel. The host pre-fetches each entity's `entity_picture` URL
 *   before capture and exposes the results through [Inline.bitmapFor]. Falls back to the card's
 *   icon path when the bitmap isn't ready yet — capture stays synchronous.
 *
 * The strategy is set by the embedding surface via [LocalPictureImageStrategy]; converters read it
 * during capture.
 */
sealed interface PictureImageStrategy {

  /**
   * URL form. Captures `addBitmapUrl(url, [widthPx], [heightPx])` + `setNamedVariable(id,
   * prefixed_name, IMAGE_TYPE)`. Player resolves the URL at playback. Dimensions are pinned because
   * the upstream `addNamedBitmapUrl(name, url)` defaults to 1×1 and paints invisible — see #277.
   */
  data class Url(val widthPx: Int, val heightPx: Int) : PictureImageStrategy

  /**
   * Bytes form. The host pre-fetches the URL and exposes the resolved [ImageBitmap] through
   * [bitmapFor]; the converter bakes it inline via `addNamedBitmap`. Use this when the embedding
   * surface can't run a `BitmapLoader` at playback — widget cards being the primary case.
   *
   * Returning `null` from [bitmapFor] tells the converter to fall back to the icon path. That keeps
   * capture synchronous and lets the host stage fetches independently.
   */
  fun interface Inline : PictureImageStrategy {
    fun bitmapFor(url: String): ImageBitmap?
  }

  companion object {
    /**
     * Sensible app-mode default. This is the slot the player enforces as a **ceiling**: a fetched
     * bitmap larger than `widthPx × heightPx` is rejected by `RemoteBitmapDecoder.checkBounds`
     * ("dimensions don't match"). HA serves `entity_picture` / `camera_proxy` frames at the
     * source's native resolution (no server-side resize), so the app-mode `BitmapLoader`
     * (`CoilBitmapLoader`) downscales fetched frames to fit this cap before handing bytes to the
     * player. Keep this in sync with `CoilBitmapLoader.DEFAULT_MAX_IMAGE_DIMENSION_PX` (512).
     */
    val DefaultAppUrl: Url = Url(widthPx = 512, heightPx = 512)
  }
}

/**
 * CompositionLocal that selects which [PictureImageStrategy] the picture-entity converter uses.
 * Defaults to [PictureImageStrategy.DefaultAppUrl] (app mode), so callers that don't override it
 * get a URL-form doc and the existing dashboard `BitmapLoader` wiring picks the bytes up at
 * playback. Widget hosts must explicitly provide a [PictureImageStrategy.Inline] with pre-fetched
 * bitmaps **inside** the capture composition (a `captureSingleRemoteDocument` content block) —
 * `CompositionLocal`s from the outer tree don't propagate into the capture pass.
 */
val LocalPictureImageStrategy =
  staticCompositionLocalOf<PictureImageStrategy> { PictureImageStrategy.DefaultAppUrl }
