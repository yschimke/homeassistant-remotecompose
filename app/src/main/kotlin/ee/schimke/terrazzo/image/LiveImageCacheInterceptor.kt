package ee.schimke.terrazzo.image

import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.request.SuccessResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Disk caching for Home Assistant's live pictures: camera frames (`/api/camera_proxy/…`) and image
 * entities (`/api/image_proxy/…`).
 *
 * Coil's default cache strategy serves any disk hit without revalidating and ignores
 * `Cache-Control`, which suits nearly everything Home Assistant serves: user pictures
 * (`/api/image/serve/…`, a month's `max-age`), media artwork (`/api/media_player_proxy/…`, its URL
 * names the artwork's hash) and `/local/` files (a month's `max-age`). These two are different:
 * their content changes in place, they send no cache headers, and their URL carries an access token
 * that Home Assistant rotates every five minutes and stops accepting after ten. Keyed on that URL,
 * each rotation wrote a new disk entry (hundreds a day per camera, crowding out pictures worth
 * keeping), and an offline start, whose cached snapshot names an expired token, found nothing.
 *
 * So these are keyed on the URL without its token, one entry per entity, and fetched network first:
 * a fresh frame replaces the entry, and when the fetch fails (offline, Home Assistant down, an
 * expired token) the last frame on disk stands in. With no network at all the disk is read straight
 * away rather than after a connect timeout.
 */
internal class LiveImageCacheInterceptor(private val isOnline: () -> Boolean) : Interceptor {

  override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
    val request = chain.request
    val key = liveImageDiskKey(request.data.toString()) ?: return chain.proceed()
    val fromDisk =
      request
        .newBuilder()
        .diskCacheKey(key)
        .diskCachePolicy(CachePolicy.READ_ONLY)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()
    if (!isOnline()) return chain.withRequest(fromDisk).proceed()
    val fresh =
      chain
        .withRequest(
          request.newBuilder().diskCacheKey(key).diskCachePolicy(CachePolicy.WRITE_ONLY).build()
        )
        .proceed()
    if (fresh is SuccessResult) return fresh
    return chain.withRequest(fromDisk).proceed().takeIf { it is SuccessResult } ?: fresh
  }
}

/**
 * The disk cache key for [url] when it is one of Home Assistant's live pictures (its URL without
 * the rotating `token`), else null.
 */
internal fun liveImageDiskKey(url: String): String? {
  val parsed = url.toHttpUrlOrNull() ?: return null
  if (!parsed.isLiveImage()) return null
  return parsed.newBuilder().removeAllQueryParameters("token").build().toString()
}

private fun HttpUrl.isLiveImage(): Boolean {
  val segments = pathSegments
  return segments.size >= 3 &&
    segments[0] == "api" &&
    (segments[1] == "camera_proxy" || segments[1] == "image_proxy")
}
