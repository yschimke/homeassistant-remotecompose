package ee.schimke.ha.rc.image

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.remote.player.core.platform.BitmapLoader
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.executeBlocking
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Non-blocking [BitmapLoader] backed by Coil. Used by the
 * RemoteCompose player to resolve named-bitmap references
 * (`RemoteHaImageNamed` / `rememberNamedRemoteBitmap(...)`).
 *
 * **Never blocks the caller.** `loadBitmap` is invoked on the player's
 * decode path; we only consult Coil's in-memory cache, so the call is
 * effectively a hash-map lookup. If the bytes are not in memory we
 * return an empty stream (the player decodes that to a null bitmap
 * and keeps the placeholder visible) and enqueue a full async fetch
 * via [ImageLoader.enqueue] — Coil's standard pipeline (network +
 * disk-cache + decode) populates the memory cache in the background,
 * and the next document update that references this name resolves
 * synchronously. Coil dedupes concurrent enqueues for the same
 * `data`, so calling [loadBitmap] every frame for a missing name
 * is cheap.
 *
 * Note: `BitmapLoader.loadBitmap` is `@NonNull`; the player wraps any
 * `IOException` in a `RuntimeException` and crashes, so we cannot
 * signal "miss" by throwing. Returning an empty `InputStream`
 * decodes to a null bitmap — the player handles that path
 * gracefully.
 *
 * `name` is whatever Coil's mapper accepts as `ImageRequest.data`:
 * `http(s)://…`, `file://…`, `content://…`, `android.resource://…`,
 * an absolute file path, etc.
 *
 * The player decodes the returned stream with `BitmapFactory.decodeStream`,
 * so we PNG-encode lossless on the way out. The player's own
 * decode-result cache means this re-encode runs at most once per
 * (id, name) pair per document.
 */
class CoilBitmapLoader(
    private val context: Context,
    private val imageLoader: ImageLoader = SingletonImageLoader.get(context),
    private val compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    private val compressQuality: Int = 100,
    private val configure: ImageRequest.Builder.() -> Unit = {},
) : BitmapLoader {

    override fun loadBitmap(name: String): InputStream {
        val syncResult = imageLoader.executeBlocking(buildSyncRequest(name))
        if (syncResult is SuccessResult) {
            return encode(syncResult.image.toBitmap())
        }
        imageLoader.enqueue(buildWarmRequest(name))
        return ByteArrayInputStream(EMPTY)
    }

    private fun buildSyncRequest(name: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(name)
            .networkCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .apply(configure)
            .build()

    private fun buildWarmRequest(name: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(name)
            .apply(configure)
            .build()

    private fun encode(bitmap: Bitmap): InputStream {
        val out = ByteArrayOutputStream(bitmap.allocationByteCount.coerceAtLeast(1024))
        bitmap.compress(compressFormat, compressQuality, out)
        return ByteArrayInputStream(out.toByteArray())
    }

    private companion object {
        private val EMPTY = ByteArray(0)
    }
}
