@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.BitmapImage
import coil3.EventListener
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.network.LanConnectionPolicyInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Process-wide image stack for HA-aware fetches.
 *
 * Owns the single [DiskCache] and single [ImageLoader] every screen shares — `DiskCache` documents
 * that **only one instance per directory** is safe (otherwise the journals can interleave and
 * corrupt). Park the binding at [AppScope] so `DashboardViewScreen` and `WidgetInstallSheet` (and
 * anyone else who renders cards) reuse the same loader instead of constructing per-screen ones
 * pointing at the same `cacheDir/ha_image_cache` path.
 *
 * The bearer-auth interceptor reads its token through a provider lambda that closes over [haHost] /
 * [accessToken], so the loader itself never has to be rebuilt when the session changes. Callers
 * push the active session via [setAuth] (`HaSession` exposes `baseUrl` + `accessToken`); the
 * interceptor picks up the new values on the next request. The session host is matched
 * case-insensitively; only same-host requests get the bearer, so external CDN thumbnails referenced
 * from a dashboard don't leak the token.
 *
 * Also exposes [resolve] for hosts that need to pre-fetch a URL to an Android [Bitmap] — used by
 * widget capture, which has no playback-time `BitmapLoader` and must bake fetched bytes into the
 * `.rc` doc inline. Same resolution rule as `CoilBitmapLoader`: a leading `/` is prefixed with the
 * current HA base URL, anything else (absolute, `content://`, …) passes through.
 */
@SingleIn(AppScope::class)
@Inject
class HaImageStack(private val context: Context, private val lanPolicy: LanConnectionPolicy) {

  @Volatile private var baseUrl: String? = null
  @Volatile private var haHost: String? = null
  @Volatile private var accessToken: String? = null

  /**
   * Push the active session's base URL + bearer token. Callers wire this from the session-change
   * point (`HaSession` exposes both values). Subsequent fetches use the new pair on the next
   * request — the interceptor reads through the provider lambdas rather than captured constants.
   */
  fun setAuth(baseUrl: String?, accessToken: String?) {
    this.baseUrl = baseUrl
    this.haHost = baseUrl?.toHttpUrlOrNull()?.host
    this.accessToken = accessToken
  }

  private val diskCache: DiskCache by lazy {
    // Pin sane bounds explicitly so the cache behaves predictably on
    // every device: at least 10 MB even when free space is tight, no
    // more than 100 MB so a long-running camera dashboard with
    // token-rotating URLs can't blow up the cache directory. Coil's
    // default `maxSizePercent` (0.02) still gates between these
    // bounds.
    DiskCache.Builder()
      .directory(context.applicationContext.cacheDir.resolve(IMAGE_CACHE_DIR).toOkioPath())
      .minimumMaxSizeBytes(MIN_DISK_CACHE_BYTES)
      .maximumMaxSizeBytes(MAX_DISK_CACHE_BYTES)
      .build()
      .also { Log.d(TAG, "diskCache built at $IMAGE_CACHE_DIR maxSize=${it.maxSize}") }
  }

  /**
   * The shared Coil loader. Lazy so a process that never renders an image (background services,
   * tests) doesn't pay the OkHttp / disk cache construction cost.
   */
  val imageLoader: ImageLoader by lazy {
    val client =
      OkHttpClient.Builder()
        .addInterceptor(LanConnectionPolicyInterceptor(lanPolicy))
        .addInterceptor(HaBearerAuthInterceptor({ haHost }, { accessToken }))
        .build()
    Log.d(TAG, "imageLoader building (host=$haHost hasToken=${accessToken != null})")
    ImageLoader.Builder(context.applicationContext)
      .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
      .diskCache(diskCache)
      .eventListener(LoggingEventListener)
      .build()
      .also { Log.d(TAG, "imageLoader built") }
  }

  /**
   * Pre-fetch the image at [url] to an Android [Bitmap]. Used by widget capture, which must bake
   * the bytes into the doc because widget runtime has no `BitmapLoader`. Returns `null` on fetch /
   * decode failure so callers can fall back to an icon path.
   */
  suspend fun resolve(url: String): Bitmap? {
    val resolved = resolveAgainstBase(url)
    Log.d(TAG, "resolve request input=$url resolved=$resolved")
    val request = ImageRequest.Builder(context).data(resolved).allowHardware(false).build()
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

  /**
   * Coil [EventListener] that logs each stage of an image request to logcat under [TAG]. Filterable
   * with
   *
   * ```
   * adb logcat -s HaImageStack
   * ```
   *
   * The default Coil pipeline runs many stages (size resolve → map → key → fetch → decode →
   * transform → transition); we only emit a line for the ones useful for diagnosing why a tile
   * stays gray: the chosen fetcher (network vs. memory cache hit), the fetch outcome with its
   * [DataSource] (so a stale fetch from `MEMORY` / `DISK` is visible), and the final success /
   * error.
   */
  private object LoggingEventListener : EventListener() {
    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
      Log.d(TAG, "fetchStart data=${request.data} fetcher=${fetcher::class.java.simpleName}")
    }

    override fun fetchEnd(
      request: ImageRequest,
      fetcher: Fetcher,
      options: Options,
      result: FetchResult?,
    ) {
      val source: DataSource? =
        when (result) {
          is SourceFetchResult -> result.dataSource
          is ImageFetchResult -> result.dataSource
          else -> null
        }
      Log.d(
        TAG,
        "fetchEnd data=${request.data} fetcher=${fetcher::class.java.simpleName} " +
          "result=${result?.let { it::class.java.simpleName } ?: "null"} dataSource=$source",
      )
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
      val img = result.image
      val dims =
        if (img is BitmapImage) "${img.bitmap.width}x${img.bitmap.height}"
        else img::class.java.simpleName
      Log.d(TAG, "onSuccess data=${request.data} dataSource=${result.dataSource} image=$dims")
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
      Log.w(
        TAG,
        "onError data=${request.data} " +
          "throwable=${result.throwable::class.java.simpleName}: ${result.throwable.message}",
      )
    }
  }

  private companion object {
    private const val IMAGE_CACHE_DIR = "ha_image_cache"
    private const val TAG = "HaImageStack"

    // 10 MB floor — even tight-storage devices keep enough room to
    // cache a screenful of dashboard thumbnails.
    private const val MIN_DISK_CACHE_BYTES = 10L * 1024 * 1024

    // 100 MB ceiling — a token-rotating camera URL is a new cache
    // key every refresh; we don't want that to balloon the cache
    // dir while the user's away. LRU eviction reclaims under the
    // ceiling.
    private const val MAX_DISK_CACHE_BYTES = 100L * 1024 * 1024
  }
}
