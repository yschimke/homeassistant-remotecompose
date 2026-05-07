package ee.schimke.ha.rc.image

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.remote.player.core.platform.BitmapLoader
import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Non-blocking [BitmapLoader] backed by Coil. Used by the
 * RemoteCompose player to resolve named-bitmap references
 * (`RemoteHaImageNamed` / `rememberNamedRemoteBitmap(...)`).
 *
 * **Never blocks.** [loadBitmap] runs on the player's decode thread,
 * so the implementation must be a hash-map lookup, not a coroutine
 * dispatch. We bypass [ImageLoader.execute] entirely (it spins up an
 * `async` job on a coroutine scope and `await`s it — even on a memory
 * hit the call goes through coroutine dispatch) and read directly
 * from [ImageLoader.memoryCache] with a key we control.
 *
 * Why not configure Coil's dispatchers instead — set
 * `interceptorCoroutineContext` (etc.) to `EmptyCoroutineContext` so
 * `executeBlocking` runs the engine on the calling thread? Two
 * reasons:
 *
 *  1. `RealImageLoader.execute` builds its `async` with
 *     `CoroutineStart.DEFAULT` (hardcoded — `BuildersKt.async$default`
 *     with `start = null`), which schedules through the dispatcher
 *     in the merged context regardless of what we pass in. Coil
 *     never starts undispatched, so the calling thread always pays
 *     for at least one dispatch round-trip.
 *  2. Coil's dispatcher knobs are per-loader, not per-call. Setting
 *     them all to direct contexts to make the lookup sync would
 *     also make [ImageLoader.enqueue] (used for the async warm-up
 *     below) run on the calling thread — defeating the point.
 *
 * Splitting at the [MemoryCache] boundary keeps both invariants:
 * lookup is a pure `HashMap.get`, and warm-up rides Coil's normal
 * `enqueue` pipeline (`interceptorCoroutineContext` →
 * `fetcherCoroutineContext` → `decoderCoroutineContext`, default
 * `Dispatchers.IO` for fetch).
 *
 * Coil's engine derives memory-cache keys from the request's data
 * **plus** size, scale, transformations, etc. — the engine-derived
 * key for `data="https://…/x.png"` does **not** equal
 * `MemoryCache.Key("https://…/x.png")`. To make the direct lookup
 * agree with what the warm-up writes, both sides use the same
 * explicit [MemoryCache.Key] via [memoryCacheKeyFor]. Override that
 * if you want to namespace per-instance.
 *
 * On a miss we:
 *
 *  1. Enqueue a full async fetch via [ImageLoader.enqueue] with the
 *     same explicit memory-cache key. Coil's pipeline (network +
 *     disk-cache + decode) populates the memory cache in the
 *     background; concurrent enqueues for the same key are deduped.
 *  2. Return an empty stream — [BitmapLoader.loadBitmap] is `@NonNull`
 *     and the player wraps thrown `IOException`s in a
 *     `RuntimeException`, so we cannot signal "miss" by throwing.
 *     `BitmapFactory.decodeStream` decodes empty input to a null
 *     bitmap and the player keeps the placeholder.
 *
 * `name` is whatever Coil's mappers accept as `ImageRequest.data`:
 * `http(s)://…`, `file://…`, `content://…`, `android.resource://…`,
 * an absolute file path, etc.
 */
open class CoilBitmapLoader(
    private val context: Context,
    private val imageLoader: ImageLoader = SingletonImageLoader.get(context),
    private val compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    private val compressQuality: Int = 100,
    private val configure: ImageRequest.Builder.() -> Unit = {},
) : BitmapLoader {

    final override fun loadBitmap(name: String): InputStream {
        val key = memoryCacheKeyFor(name)
        val image = imageLoader.memoryCache?.get(key)?.image
        val bytes = image?.let(::encodeImage)
        if (bytes != null) return ByteArrayInputStream(bytes)
        warmUpAsync(name, key)
        return ByteArrayInputStream(EMPTY)
    }

    /**
     * Stable key used both for the direct memory-cache lookup and for
     * the warm-up [ImageRequest.memoryCacheKey], so writes and reads
     * agree.
     */
    protected open fun memoryCacheKeyFor(name: String): MemoryCache.Key = MemoryCache.Key(name)

    /**
     * Convert the cached [Image] to PNG (or [compressFormat]) bytes,
     * or `null` if the cached image is not a [BitmapImage] (e.g. a
     * `ColorImage` placeholder Coil keeps in some configurations).
     * Override to support additional [Image] subtypes or to swap the
     * encoder.
     */
    protected open fun encodeImage(image: Image): ByteArray? {
        if (image !is BitmapImage) return null
        val out = ByteArrayOutputStream(image.bitmap.allocationByteCount.coerceAtLeast(1024))
        image.bitmap.compress(compressFormat, compressQuality, out)
        return out.toByteArray()
    }

    /**
     * Kick off a full Coil fetch in the background. Default uses
     * [ImageLoader.enqueue] which fires-and-forgets; the populated
     * memory-cache entry is what [loadBitmap] consults next time.
     */
    protected open fun warmUpAsync(name: String, key: MemoryCache.Key) {
        val request =
            ImageRequest.Builder(context).data(name).memoryCacheKey(key).apply(configure).build()
        imageLoader.enqueue(request)
    }

    private companion object {
        private val EMPTY = ByteArray(0)
    }
}
