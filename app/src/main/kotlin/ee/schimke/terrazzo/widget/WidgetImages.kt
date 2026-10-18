package ee.schimke.terrazzo.widget

import android.content.Context
import android.graphics.Bitmap
import ee.schimke.terrazzo.terrazzoGraph
import java.io.ByteArrayOutputStream

/**
 * Pictures for hosts that can't fetch one — launcher widgets and Wear widgets embed them: fetched
 * through the app's Home Assistant image stack and PNG-encoded (the format `rc-player-edit`
 * embeds).
 */
object WidgetImages {

  /**
   * [url] on [baseUrl] as PNG bytes scaled to fit [maxPx], halved again until they fit [maxBytes];
   * null when it can't be fetched or made small enough. [fresh] skips cached copies, for a periodic
   * refresh of a URL whose image changes in place.
   */
  suspend fun png(
    context: Context,
    baseUrl: String,
    url: String,
    maxPx: Int,
    maxBytes: Int = Int.MAX_VALUE,
    fresh: Boolean = false,
  ): ByteArray? {
    val stack = (context.terrazzoGraph() as? ee.schimke.terrazzo.di.AppGraph)?.haImageStack
    stack ?: return null
    stack.ensureBaseUrl(baseUrl)
    val bitmap = stack.resolve(target(baseUrl, url), fresh, maxDimensionPx = maxPx) ?: return null
    return encode(bitmap, maxPx, maxBytes)
  }

  /**
   * [url] pinned to the widget's own server [baseUrl] when it is server-relative, as Home
   * Assistant's `entity_picture` / `camera_proxy` / `image_proxy` URLs are. The image stack's base
   * is the foreground session's, which need not be this widget's: a demo widget refreshing while
   * the app shows a real server, or a real widget refreshing while the app is in demo mode, would
   * otherwise fetch from the other one (and a real widget would get a demo stand-in). Same-host
   * requests still carry the session's bearer; signed picture URLs carry their own token.
   */
  internal fun target(baseUrl: String, url: String): String =
    if (url.startsWith('/')) baseUrl.trimEnd('/') + url else url

  /** [bitmap] as PNG scaled to fit [maxPx], halved until it fits [maxBytes]; null if it can't. */
  fun encode(bitmap: Bitmap, maxPx: Int, maxBytes: Int = Int.MAX_VALUE): ByteArray? {
    var limit = maxPx
    while (limit >= MIN_PX) {
      val png = scaled(bitmap, limit).toPng()
      if (png.size <= maxBytes) return png
      limit /= 2
    }
    return null
  }

  private fun scaled(bitmap: Bitmap, maxPx: Int): Bitmap {
    val scale = minOf(1f, maxPx.toFloat() / maxOf(bitmap.width, bitmap.height))
    if (scale >= 1f) return bitmap
    return Bitmap.createScaledBitmap(
      bitmap,
      (bitmap.width * scale).toInt().coerceAtLeast(1),
      (bitmap.height * scale).toInt().coerceAtLeast(1),
      true,
    )
  }

  private fun Bitmap.toPng(): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

  private const val MIN_PX = 32
}
