@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import ee.schimke.ha.rc.RemoteImageResolver

/**
 * [RemoteImageResolver] backed by a per-session Coil [ImageLoader].
 *
 * Refreshes picture-entity tiles in a running player: when an entity's
 * `entity_picture` rotates, `CachedCardPreview` calls [resolve] with
 * the new URL, awaits the bitmap, and pushes it through
 * `StateUpdater.setUserLocalBitmap`. The override sticks across paint
 * — the original URL stays baked in the document, so first-paint and
 * subsequent rotations both render fresh bytes without re-encoding the
 * `.rc`.
 *
 * Reuses the session's [imageLoader] so cache, auth (bearer), and LAN
 * policy are shared with the player's `BitmapLoader` warm-up path.
 * Server-relative URLs (`/api/camera_proxy/...?token=...`) are
 * resolved against [baseUrl] before the fetch, mirroring
 * `CoilBitmapLoader`'s resolution rule.
 *
 * `allowHardware(false)` so the returned `Bitmap` has CPU-side pixels
 * — `setUserLocalBitmap` reads them on the player's render thread,
 * and hardware bitmaps would throw on draw paths that need them
 * upload-able.
 */
class HaCoilImageResolver(
  private val context: Context,
  private val imageLoader: ImageLoader,
  private val baseUrl: String?,
) : RemoteImageResolver {

  private val baseUrlPrefix: String? = baseUrl?.trimEnd('/')

  override suspend fun resolve(url: String): Bitmap? {
    val resolved = resolveAgainstBase(url)
    Log.d(TAG, "resolve request input=$url resolved=$resolved")
    val request = ImageRequest.Builder(context).data(resolved).allowHardware(false).build()
    val result = imageLoader.execute(request)
    when (result) {
      is SuccessResult -> {
        val img = result.image
        val bitmap = (img as? BitmapImage)?.bitmap
        if (bitmap != null) {
          Log.d(
            TAG,
            "resolve SUCCESS input=$url resolved=$resolved " +
              "bitmap=${bitmap.width}x${bitmap.height} dataSource=${result.dataSource}",
          )
        } else {
          Log.w(
            TAG,
            "resolve UNEXPECTED input=$url resolved=$resolved " +
              "image=${img::class.java.simpleName} (not a BitmapImage)",
          )
        }
        return bitmap
      }
      is ErrorResult -> {
        Log.w(
          TAG,
          "resolve ERROR input=$url resolved=$resolved " +
            "throwable=${result.throwable::class.java.simpleName}: ${result.throwable.message}",
        )
        return null
      }
    }
  }

  private fun resolveAgainstBase(url: String): String {
    val prefix = baseUrlPrefix ?: return url
    if (!url.startsWith('/')) return url
    return prefix + url
  }

  private companion object {
    private const val TAG = "HaCoilImageResolver"
  }
}
