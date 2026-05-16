@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.rc.RemoteImageResolver
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.network.LanConnectionPolicyInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Process-wide image stack for HA-aware fetches.
 *
 * Owns the single [DiskCache] and single [ImageLoader] every screen
 * shares — `DiskCache` documents that **only one instance per
 * directory** is safe (otherwise the journals can interleave and
 * corrupt). Park the binding at [AppScope] so `DashboardViewScreen`
 * and `WidgetInstallSheet` (and anyone else who renders cards) reuse
 * the same loader instead of constructing per-screen ones pointing at
 * the same `cacheDir/ha_image_cache` path.
 *
 * The bearer-auth interceptor reads its token through a provider
 * lambda that closes over [haHost] / [accessToken], so the loader
 * itself never has to be rebuilt when the session changes. Callers
 * push the active session via [setAuth] (`HaSession` exposes
 * `baseUrl` + `accessToken`); the interceptor picks up the new
 * values on the next request. The session host is matched
 * case-insensitively; only same-host requests get the bearer, so
 * external CDN thumbnails referenced from a dashboard don't leak the
 * token.
 *
 * Also implements [RemoteImageResolver] so `CachedCardPreview` can
 * push fresh picture-entity bitmaps via
 * `StateUpdater.setUserLocalBitmap` without re-capturing the
 * document. Same resolution rule as `CoilBitmapLoader`: a leading
 * `/` is prefixed with the current HA base URL, anything else
 * (absolute, `content://`, …) passes through.
 */
@SingleIn(AppScope::class)
@Inject
class HaImageStack(
  private val context: Context,
  private val lanPolicy: LanConnectionPolicy,
) : RemoteImageResolver {

  @Volatile private var baseUrl: String? = null
  @Volatile private var haHost: String? = null
  @Volatile private var accessToken: String? = null

  /**
   * Push the active session's base URL + bearer token. Callers wire
   * this from the session-change point (`HaSession` exposes both
   * values). Subsequent fetches use the new pair on the next request
   * — the interceptor reads through the provider lambdas rather than
   * captured constants.
   */
  fun setAuth(baseUrl: String?, accessToken: String?) {
    this.baseUrl = baseUrl
    this.haHost = baseUrl?.toHttpUrlOrNull()?.host
    this.accessToken = accessToken
  }

  private val diskCache: DiskCache by lazy {
    DiskCache.Builder()
      .directory(context.applicationContext.cacheDir.resolve(IMAGE_CACHE_DIR).toOkioPath())
      .build()
  }

  /**
   * The shared Coil loader. Lazy so a process that never renders an
   * image (background services, tests) doesn't pay the OkHttp / disk
   * cache construction cost.
   */
  val imageLoader: ImageLoader by lazy {
    val client =
      OkHttpClient.Builder()
        .addInterceptor(LanConnectionPolicyInterceptor(lanPolicy))
        .addInterceptor(HaBearerAuthInterceptor({ haHost }, { accessToken }))
        .build()
    ImageLoader.Builder(context.applicationContext)
      .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
      .diskCache(diskCache)
      .build()
  }

  override suspend fun resolve(url: String): Bitmap? {
    val resolved = resolveAgainstBase(url)
    Log.d(TAG, "resolve request input=$url resolved=$resolved")
    val request =
      ImageRequest.Builder(context).data(resolved).allowHardware(false).build()
    return when (val result = imageLoader.execute(request)) {
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
        bitmap
      }
      is ErrorResult -> {
        Log.w(
          TAG,
          "resolve ERROR input=$url resolved=$resolved " +
            "throwable=${result.throwable::class.java.simpleName}: ${result.throwable.message}",
        )
        null
      }
    }
  }

  private fun resolveAgainstBase(url: String): String {
    val prefix = baseUrl?.trimEnd('/') ?: return url
    if (!url.startsWith('/')) return url
    return prefix + url
  }

  private companion object {
    private const val IMAGE_CACHE_DIR = "ha_image_cache"
    private const val TAG = "HaImageStack"
  }
}
