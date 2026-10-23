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
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.ha.client.SharedOkHttpClient
import ee.schimke.terrazzo.core.di.AppScope
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.network.LanConnectionPolicyInterceptor
import ee.schimke.terrazzo.core.network.RemoteUrlInterceptor
import ee.schimke.terrazzo.core.network.RemoteUrlStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Path.Companion.toOkioPath

/**
 * Process-wide image stack for HA-aware fetches.
 *
 * Owns the single [DiskCache] and single [ImageLoader] every screen shares — `DiskCache` documents
 * that **only one instance per directory** is safe (otherwise the journals can interleave and
 * corrupt). Park the binding at [AppScope] so `DashboardViewScreen` and `WidgetInstallSheet` (and
 * anyone else who renders cards) reuse the same loader instead of constructing per-screen ones
 * pointing at the same `noBackupFilesDir/terrazzo/images` path.
 *
 * The bearer-auth interceptor reads its token through a provider lambda that closes over [baseUrl]
 * / [accessToken], so the loader itself never has to be rebuilt when the session changes. Callers
 * push the active session via [setAuth] (`HaSession` exposes `baseUrl` + `accessToken`); the
 * interceptor picks up the new values on the next request. Only requests on the session's origin
 * (host, effective port, no https-to-http downgrade) get the bearer, so external CDN thumbnails and
 * other services on the same host don't receive the token.
 *
 * Also exposes [resolve] for hosts that need to pre-fetch a URL to an Android [Bitmap] — used by
 * widget capture, which has no playback-time `BitmapLoader` and must bake fetched bytes into the
 * `.rc` doc inline. Same resolution rule as `CoilBitmapLoader`: a leading `/` is prefixed with the
 * current HA base URL, anything else (absolute, `content://`, …) passes through.
 */
@SingleIn(AppScope::class)
@Inject
class HaImageStack(
  private val context: Context,
  private val lanPolicy: LanConnectionPolicy,
  private val remoteUrls: RemoteUrlStore,
) {

  @Volatile private var baseUrl: String? = null
  @Volatile private var accessToken: String? = null

  /**
   * Push the active session's base URL + bearer token. Callers wire this from the session-change
   * point (`HaSession` exposes both values). Subsequent fetches use the new pair on the next
   * request — the interceptor reads through the provider lambdas rather than captured constants.
   */
  fun setAuth(baseUrl: String?, accessToken: String?) {
    this.baseUrl = baseUrl
    this.accessToken = accessToken
  }

  /**
   * Point relative URLs at [baseUrl] when no session has set it yet (a widget update in a process
   * whose UI never ran). Leaves an existing session's base and token alone. Signed `entity_picture`
   * / `camera_proxy` URLs carry their own token, so they need no bearer.
   */
  fun ensureBaseUrl(baseUrl: String) {
    if (this.baseUrl == null) setAuth(baseUrl, accessToken)
  }

  private val diskCache: DiskCache by lazy {
    // Pin sane bounds explicitly so the cache behaves predictably on
    // every device: at least 10 MB even when free space is tight, no
    // more than 100 MB so a long-running camera dashboard with
    // token-rotating URLs can't blow up the cache directory. Coil's
    // default `maxSizePercent` (0.02) still gates between these
    // bounds.
    // In app storage rather than cacheDir, which the system empties when storage runs low: the
    // pictures are what an offline dashboard draws, and the bounds below already keep it small.
    // Out of backups too; it is all refetchable.
    val app = context.applicationContext
    val directory = app.noBackupFilesDir.resolve(IMAGE_CACHE_DIR)
    // Keep what an earlier build cached under cacheDir.
    val legacy = app.cacheDir.resolve(LEGACY_IMAGE_CACHE_DIR)
    if (legacy.isDirectory && !directory.exists()) {
      directory.parentFile?.mkdirs()
      if (!legacy.renameTo(directory)) legacy.deleteRecursively()
    }
    DiskCache.Builder()
      .directory(directory.toOkioPath())
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
      // A variant of the app's one client, sharing its connection pool, adding the image-only
      // interceptors.
      SharedOkHttpClient.client
        .newBuilder()
        // The bearer goes on by the session's own origin first; a LAN URL off the home network is
        // then rewritten to the instance's external URL, which takes the same token, rather than
        // refused. The disk cache keys on the original URL, so either way a cached picture hits.
        .addInterceptor(HaBearerAuthInterceptor({ baseUrl }, { accessToken }))
        .addInterceptor(RemoteUrlInterceptor(lanPolicy, remoteUrls))
        .addInterceptor(LanConnectionPolicyInterceptor(lanPolicy))
        .build()
    Log.d(
      TAG,
      "imageLoader building (host=${baseUrl?.toHttpUrlOrNull()?.host} hasToken=${accessToken != null})",
    )
    ImageLoader.Builder(context.applicationContext)
      .components {
        // Camera and image-entity pictures: one disk entry per entity, network first, the last
        // one on disk when offline. Other pictures that can change in place refresh once a day.
        add(
          LiveImageCacheInterceptor(
            isOnline = { lanPolicy.isOnline },
            savedAtMs = { key ->
              diskCache.openSnapshot(key)?.use { it.data.toFile().lastModified() }
            },
          )
        )
        // Demo mode's pictures are on a host nothing serves; draw stand-ins for them. Answers only
        // for `DemoData.BASE_URL` URLs, so real sessions' fetches are untouched.
        add(DemoImageFetcher.Factory())
        add(OkHttpNetworkFetcherFactory(callFactory = { client }))
      }
      .diskCache(diskCache)
      .eventListener(LoggingEventListener)
      .build()
      .also { Log.d(TAG, "imageLoader built") }
  }

  /**
   * Forgets every picture, in memory and on disk: sign-out calls it so the next account on the
   * device can't see the last one's camera frames and people. The disk cache is in app storage that
   * only this clears.
   */
  suspend fun clearCaches() {
    imageLoader.memoryCache?.clear()
    withContext(Dispatchers.IO) { diskCache.clear() }
  }

  /**
   * Fetches into the disk cache whichever of [urls] it doesn't hold yet, so a card that hasn't been
   * on screen still has its picture the next time the app starts offline. Memory stays out of it:
   * no memory-cache entry, and a token-sized decode (Coil decodes what it fetches; the disk keeps
   * the bytes as served). Checks the disk on [Dispatchers.IO]; the fetches themselves are enqueued.
   */
  suspend fun prewarm(urls: Collection<String>) {
    if (urls.isEmpty() || !lanPolicy.isOnline) return
    withContext(Dispatchers.IO) { enqueueMissing(urls) }
  }

  private fun enqueueMissing(urls: Collection<String>) {
    for (url in urls) {
      val resolved = resolveAgainstBase(url)
      if (resolved.startsWith('/')) continue
      val key = liveImageDiskKey(resolved) ?: resolved
      if (diskCache.openSnapshot(key)?.use { true } == true) continue
      imageLoader.enqueue(
        ImageRequest.Builder(context)
          .data(resolved)
          .memoryCachePolicy(CachePolicy.DISABLED)
          .size(1, 1)
          .allowHardware(false)
          .build()
      )
    }
  }

  /**
   * Pre-fetch the image at [url] to an Android [Bitmap]. Used by widget capture, which must bake
   * the bytes into the doc because widget runtime has no `BitmapLoader`. Returns `null` on fetch /
   * decode failure so callers can fall back to an icon path. With [fresh], cached copies are
   * skipped (the result is still cached): for a periodic refresh of a URL whose image changes in
   * place, such as a camera proxy.
   */
  suspend fun resolve(url: String, fresh: Boolean = false, maxDimensionPx: Int? = null): Bitmap? {
    val resolved = resolveAgainstBase(url)
    Log.d(TAG, "resolve request fresh=$fresh")
    val request =
      ImageRequest.Builder(context)
        .data(resolved)
        .allowHardware(false)
        .apply {
          maxDimensionPx?.let { size(it, it) }
          if (fresh) {
            memoryCachePolicy(CachePolicy.WRITE_ONLY)
            diskCachePolicy(CachePolicy.WRITE_ONLY)
          }
        }
        .build()
    return when (val result = imageLoader.execute(request)) {
      is SuccessResult -> {
        val img = result.image
        val bitmap = (img as? BitmapImage)?.bitmap
        if (bitmap != null) {
          Log.d(
            TAG,
            "resolve SUCCESS " +
              "bitmap=${bitmap.width}x${bitmap.height} dataSource=${result.dataSource}",
          )
        } else {
          Log.w(
            TAG,
            "resolve UNEXPECTED " + "image=${img::class.java.simpleName} (not a BitmapImage)",
          )
        }
        bitmap
      }
      is ErrorResult -> {
        Log.w(
          TAG,
          "resolve ERROR throwable=${result.throwable::class.java.simpleName}",
        )
        null
      }
    }
  }

  /** [url] with a leading `/` prefixed by the active session's base URL; anything else as is. */
  internal fun resolveAgainstBase(url: String): String {
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
      Log.d(TAG, "fetchStart fetcher=${fetcher::class.java.simpleName}")
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
        "fetchEnd fetcher=${fetcher::class.java.simpleName} " +
          "result=${result?.let { it::class.java.simpleName } ?: "null"} dataSource=$source",
      )
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
      val img = result.image
      val dims =
        if (img is BitmapImage) "${img.bitmap.width}x${img.bitmap.height}"
        else img::class.java.simpleName
      Log.d(TAG, "onSuccess dataSource=${result.dataSource} image=$dims")
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
      Log.w(
        TAG,
        "onError throwable=${result.throwable::class.java.simpleName}",
      )
    }
  }

  private companion object {
    private const val IMAGE_CACHE_DIR = "terrazzo/images"

    /** Where builds before the move kept it, under cacheDir. */
    private const val LEGACY_IMAGE_CACHE_DIR = "ha_image_cache"
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
