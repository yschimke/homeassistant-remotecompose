package ee.schimke.terrazzo.image

import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.request.SuccessResult
import ee.schimke.terrazzo.dashboard.LiveCameras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *
 * Everything else that can change at one URL ([refreshesDaily]) keeps Coil's disk-first rule until
 * its copy is [REFRESH_AFTER_MS] old, then goes network first the same way. Coil itself never
 * revalidates, so without this an edited `/local/` file would stay stale until evicted.
 */
internal class LiveImageCacheInterceptor(
  private val isOnline: () -> Boolean,
  private val savedAtMs: (diskKey: String) -> Long? = { null },
  private val nowMs: () -> Long = System::currentTimeMillis,
) : Interceptor {

  override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
    val url = chain.request.data.toString()
    liveImageDiskKey(url)?.let {
      return networkFirst(chain, it)
    }
    // Anything else that can change at the same URL (`/local/` files, other sites' pictures) is
    // served from disk as Coil does, until its copy is a day old: then fetched again, the old copy
    // standing in when that fails. Looked up off the main thread, and only when Coil's memory
    // cache missed (the players read it themselves first).
    if (!refreshesDaily(url) || !isOnline()) return chain.proceed()
    val key = chain.request.diskCacheKey ?: url
    val saved = withContext(Dispatchers.IO) { savedAtMs(key) } ?: return chain.proceed()
    if (nowMs() - saved < REFRESH_AFTER_MS) return chain.proceed()
    return networkFirst(chain, key)
  }

  /** A fresh copy into [key]'s disk entry, or the entry when the network fails or is down. */
  private suspend fun networkFirst(chain: Interceptor.Chain, key: String): ImageResult {
    val request = chain.request
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

  companion object {
    /**
     * How old a disk copy of a picture that can change in place gets before it is fetched again.
     */
    const val REFRESH_AFTER_MS: Long = 24 * 60 * 60_000L
  }
}

/**
 * Whether [url] is a picture that can change while its URL stays the same, so its disk copy is
 * refreshed daily: any http(s) URL except Home Assistant's content-addressed ones (an uploaded
 * picture's id, a media player artwork's hash) and its live ones, which have their own rule.
 */
internal fun refreshesDaily(url: String): Boolean {
  val parsed = url.toHttpUrlOrNull() ?: return false
  if (parsed.isLiveImage()) return false
  val segments = parsed.pathSegments
  val contentAddressed =
    segments.size >= 2 &&
      segments[0] == "api" &&
      (segments[1] == "image" ||
        (segments[1] == "media_player_proxy" && parsed.queryParameter("cache") != null))
  return !contentAddressed
}

/**
 * The disk cache key for [url] when it is one of Home Assistant's live pictures (its URL without
 * the rotating `token`), else null.
 */
internal fun liveImageDiskKey(url: String): String? {
  val parsed = url.toHttpUrlOrNull() ?: return null
  if (!parsed.isLiveImage()) return null
  return parsed
    .newBuilder()
    .removeAllQueryParameters("token")
    // A live camera's frame counter (LiveCameras): still one entry per camera.
    .removeAllQueryParameters(LiveCameras.FRAME_PARAM)
    .build()
    .toString()
}

private fun HttpUrl.isLiveImage(): Boolean {
  val segments = pathSegments
  return segments.size >= 3 &&
    segments[0] == "api" &&
    (segments[1] == "camera_proxy" || segments[1] == "image_proxy")
}
