@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.remote.player.core.platform.BitmapLoader
import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-blocking [BitmapLoader] backed by Coil. Used by the RemoteCompose player to resolve
 * named-bitmap references (`RemoteHaImageNamed` / `rememberNamedRemoteBitmap(...)`).
 *
 * **Never blocks.** [loadBitmap] runs on the player's decode thread, so the implementation must be
 * a hash-map lookup, not a coroutine dispatch. We bypass [ImageLoader.execute] entirely (it spins
 * up an `async` job on a coroutine scope and `await`s it — even on a memory hit the call goes
 * through coroutine dispatch) and read directly from [ImageLoader.memoryCache] with a key we
 * control.
 *
 * Why not configure Coil's dispatchers instead — set `interceptorCoroutineContext` (etc.) to
 * `EmptyCoroutineContext` so `executeBlocking` runs the engine on the calling thread? Two reasons:
 *
 * 1. `RealImageLoader.execute` builds its `async` with `CoroutineStart.DEFAULT` (hardcoded —
 *    `BuildersKt.async$default` with `start = null`), which schedules through the dispatcher in the
 *    merged context regardless of what we pass in. Coil never starts undispatched, so the calling
 *    thread always pays for at least one dispatch round-trip.
 * 2. Coil's dispatcher knobs are per-loader, not per-call. Setting them all to direct contexts to
 *    make the lookup sync would also make [ImageLoader.enqueue] (used for the async warm-up below)
 *    run on the calling thread — defeating the point.
 *
 * Splitting at the [MemoryCache] boundary keeps both invariants: lookup is a pure `HashMap.get`,
 * and warm-up rides Coil's normal `enqueue` pipeline (`interceptorCoroutineContext` →
 * `fetcherCoroutineContext` → `decoderCoroutineContext`, default `Dispatchers.IO` for fetch).
 *
 * Coil's engine derives memory-cache keys from the request's data **plus** size, scale,
 * transformations, etc. — the engine-derived key for `data="https://…/x.png"` does **not** equal
 * `MemoryCache.Key("https://…/x.png")`. To make the direct lookup agree with what the warm-up
 * writes, both sides use the same explicit [MemoryCache.Key] via [memoryCacheKeyFor]. Override that
 * if you want to namespace per-instance.
 *
 * On a miss we:
 *
 * 1. Enqueue a full async fetch via [ImageLoader.enqueue] with the same explicit memory-cache key.
 *    Coil's pipeline (network + disk-cache + decode) populates the memory cache in the background;
 *    concurrent enqueues for the same key are deduped.
 * 2. Return an empty stream — [BitmapLoader.loadBitmap] is `@NonNull` and the player wraps thrown
 *    `IOException`s in a `RuntimeException`, so we cannot signal "miss" by throwing.
 *    `BitmapFactory.decodeStream` decodes empty input to a null bitmap and the player keeps the
 *    placeholder.
 *
 * `name` is whatever Coil's mappers accept as `ImageRequest.data`: `http(s)://…`, `file://…`,
 * `content://…`, `android.resource://…`, an absolute file path, etc.
 *
 * Home Assistant returns `entity_picture` (and addon icon paths, image_proxy URLs, …) as
 * **server-relative** strings like `/api/camera_proxy/camera.foo?token=…`. Coil's `HttpUriFetcher`
 * can't resolve a path-only URI, so a relative `name` would fetch to silent error and the memory
 * cache would stay empty. Pass [baseUrl] to resolve names starting with `/` against the HA server's
 * origin before the cache lookup / enqueue; keep the document itself host-agnostic.
 *
 * **Decode-bound clamp ([maxImageDimensionPx]).** The player rejects a fetched bitmap whose decoded
 * size is *larger* than the slot the document declared via `addBitmapUrl(url, w, h)`:
 * `RemoteBitmapDecoder.checkBounds` throws `dimensions don't match <fetched> vs <slot>` when
 * `fetchedW > w || fetchedH > h`. Home Assistant serves `entity_picture` / `camera_proxy` frames at
 * the source's native resolution (e.g. a 640×480 camera), with no server-side resize — so a native
 * frame routinely overflows a picture-entity's `512×512` slot and the tile renders black with that
 * red error. The loader doesn't learn the per-slot dimensions (`loadBitmap` only gets the URL), so
 * we clamp every encoded bitmap to a single longest-edge cap: anything larger is downscaled
 * (aspect-preserving) so it fits within `maxImageDimensionPx × maxImageDimensionPx`; anything
 * already within is passed through untouched. The clamp is **downscale-only** — small images
 * (markdown badges, icons) are never upscaled, so they keep fitting their own smaller slots exactly
 * as before. Keep the default in sync with `PictureImageStrategy.DefaultAppUrl` (512); a cap larger
 * than the smallest slot dimension would let a frame overflow that slot.
 */
open class CoilBitmapLoader(
  private val context: Context,
  private val imageLoader: ImageLoader = SingletonImageLoader.get(context),
  private val baseUrl: String? = null,
  private val compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
  private val compressQuality: Int = 100,
  private val maxImageDimensionPx: Int = DEFAULT_MAX_IMAGE_DIMENSION_PX,
  private val configure: ImageRequest.Builder.() -> Unit = {},
) : BitmapLoader {

  private val baseUrlPrefix: String? = baseUrl?.trimEnd('/')

  /**
   * Per-name diagnostic state so [loadBitmap]'s hot path doesn't spam logcat at the player's
   * redecode cadence (~125 Hz). We only emit a log line on a state transition (`null` → miss, miss
   * → hit, hit → miss). Keys are the resolved URL so different sources for the same name still log
   * if they diverge.
   */
  private val logState = ConcurrentHashMap<String, LogState>()

  final override fun loadBitmap(name: String): InputStream {
    val resolved = resolveName(name)
    val key = memoryCacheKeyFor(resolved)
    val image = imageLoader.memoryCache?.get(key)?.image
    val bytes = image?.let(::encodeImage)
    if (bytes != null) {
      logTransition(resolved, LogState.Hit, bytes.size, name)
      return ByteArrayInputStream(bytes)
    }
    logTransition(resolved, LogState.Miss, 0, name)
    warmUpAsync(resolved, key)
    return ByteArrayInputStream(EMPTY)
  }

  private fun logTransition(resolved: String, state: LogState, bytes: Int, originalName: String) {
    val prev = logState.put(resolved, state)
    if (prev == state) return
    when (state) {
      LogState.Hit ->
        Log.d(TAG, "loadBitmap HIT name=$originalName resolved=$resolved bytes=$bytes")
      LogState.Miss ->
        Log.d(TAG, "loadBitmap MISS name=$originalName resolved=$resolved (enqueuing warm-up)")
    }
  }

  /**
   * Resolve a server-relative `name` against [baseUrl]; pass anything else through unchanged. Names
   * with a scheme (`http(s)://`, `content://`, `file://`, `android.resource://`, …) or that don't
   * start with `/` are returned as-is.
   */
  private fun resolveName(name: String): String {
    val prefix = baseUrlPrefix ?: return name
    if (!name.startsWith('/')) return name
    return prefix + name
  }

  /**
   * Stable key used both for the direct memory-cache lookup and for the warm-up
   * [ImageRequest.memoryCacheKey], so writes and reads agree. The argument is the **resolved** URL,
   * not the raw `name` passed to [loadBitmap], so subclasses that override this see the same string
   * the warm-up will use as request data.
   */
  protected open fun memoryCacheKeyFor(name: String): MemoryCache.Key = MemoryCache.Key(name)

  /**
   * Convert the cached [Image] to PNG (or [compressFormat]) bytes, or `null` if the cached image is
   * not a [BitmapImage] (e.g. a `ColorImage` placeholder Coil keeps in some configurations).
   * Override to support additional [Image] subtypes or to swap the encoder.
   *
   * Hardware bitmaps ([Bitmap.Config.HARDWARE], API 26+) are GPU textures with no CPU-side pixel
   * data — `Bitmap.compress` on them returns `false` or throws `IllegalStateException` depending on
   * the device, which would crash playback on a cache hit. Coil 3 stores hardware bitmaps in the
   * memory cache by default whenever the device supports it. We can't assume the host's
   * [imageLoader] was built with `allowHardware(false)` (it's typically a shared singleton), so
   * copy to a software config before encoding. Our own [warmUpAsync] disables hardware bitmaps to
   * avoid this copy on the common path.
   */
  protected open fun encodeImage(image: Image): ByteArray? {
    if (image !is BitmapImage) return null
    val source = image.bitmap
    val software =
      if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ false) ?: return null
      } else {
        source
      }
    // Clamp to the slot ceiling so the player's checkBounds accepts the bytes — see the class
    // doc. Downscale-only: smaller images return [software] unchanged.
    val encodable = downscaleToFit(software)
    val out = ByteArrayOutputStream(encodable.allocationByteCount.coerceAtLeast(1024))
    encodable.compress(compressFormat, compressQuality, out)
    // Recycle only bitmaps we allocated here, never the caller's cached [source]: the scaled copy
    // (if any) first, then the hardware→software copy (if any). The chained `!==` checks avoid a
    // double recycle when no copy/scale happened.
    if (encodable !== software) encodable.recycle()
    if (software !== source) software.recycle()
    return out.toByteArray()
  }

  /**
   * Aspect-preserving downscale so the longest edge is at most [maxImageDimensionPx]. Returns
   * [bitmap] unchanged when it already fits (or the cap is non-positive) — never upscales, so small
   * images keep their native size and their own smaller slots.
   */
  private fun downscaleToFit(bitmap: Bitmap): Bitmap {
    val cap = maxImageDimensionPx
    if (cap <= 0) return bitmap
    val w = bitmap.width
    val h = bitmap.height
    if (w <= cap && h <= cap) return bitmap
    val scale = cap.toFloat() / maxOf(w, h)
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newW, newH, /* filter= */ true)
  }

  /**
   * Kick off a full Coil fetch in the background. Default uses [ImageLoader.enqueue] which
   * fires-and-forgets; the populated memory-cache entry is what [loadBitmap] consults next time.
   *
   * `allowHardware(false)` is set so the cached `Bitmap` is a software config we can
   * [Bitmap.compress] directly — see [encodeImage]. Callers' [configure] block runs after, so a
   * caller that explicitly wants hardware bitmaps can re-enable them (and accept the copy in
   * [encodeImage]).
   */
  protected open fun warmUpAsync(name: String, key: MemoryCache.Key) {
    val request =
      ImageRequest.Builder(context)
        .data(name)
        .memoryCacheKey(key)
        .allowHardware(false)
        .listener(
          onSuccess = { _, result: SuccessResult ->
            val img = result.image
            val dims =
              if (img is BitmapImage) "${img.bitmap.width}x${img.bitmap.height}"
              else img::class.java.simpleName
            Log.d(TAG, "warmUp SUCCESS url=$name dataSource=${result.dataSource} image=$dims")
          },
          onError = { _, result: ErrorResult ->
            Log.w(
              TAG,
              "warmUp ERROR url=$name " +
                "throwable=${result.throwable::class.java.simpleName}: " +
                "${result.throwable.message}",
            )
          },
        )
        .apply(configure)
        .build()
    Log.d(TAG, "warmUp enqueue url=$name")
    imageLoader.enqueue(request)
  }

  private enum class LogState {
    Hit,
    Miss,
  }

  companion object {
    private const val TAG = "CoilBitmapLoader"
    private val EMPTY = ByteArray(0)

    /**
     * Default longest-edge cap for fetched bitmaps. Matches `PictureImageStrategy.DefaultAppUrl`
     * (512×512), the slot picture-entity cards declare; keep the two in sync.
     */
    const val DEFAULT_MAX_IMAGE_DIMENSION_PX = 512
  }
}
