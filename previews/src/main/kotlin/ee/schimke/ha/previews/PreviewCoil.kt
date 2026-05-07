@file:Suppress("RestrictedApi")
@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package ee.schimke.ha.previews

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.test.FakeImageLoaderEngine
import ee.schimke.ha.rc.image.CoilBitmapLoader

/**
 * Build a [CoilBitmapLoader] for previews / tests that resolves named
 * bitmaps through a [FakeImageLoaderEngine] — never the network,
 * never disk — and pre-populates the memory cache so the synchronous
 * lookup hits on the first call.
 *
 * `RemoteHaImageNamed` writes a `addNamedBitmapUrl(prefixedName, url)`
 * op into the document; the player passes the **URL** verbatim to
 * `BitmapLoader.loadBitmap(url)` — the registry name is internal
 * and not part of the loader call. Mappings are therefore keyed by
 * URL, with no domain prefix applied here.
 *
 * Two layers of safety:
 *
 *  1. The loader's [ImageLoader] is wired with [FakeImageLoaderEngine]
 *     as its sole engine, so any code path that reaches
 *     [ImageLoader.execute] / [ImageLoader.enqueue] is intercepted
 *     before any fetcher / decoder runs.
 *  2. Each URL is `set` directly into the memory cache under
 *     `MemoryCache.Key(url)`, matching
 *     `CoilBitmapLoader.memoryCacheKeyFor`. The synchronous lookup
 *     hits these entries — no waiting on a coroutine, no async
 *     warm-up needed.
 */
fun previewCoilBitmapLoader(
    context: Context,
    mappings: Map<String, ImageBitmap>,
): CoilBitmapLoader {
    val androidMappings = mappings.mapValues { (_, b) -> b.asAndroidBitmap() }
    val engine =
        FakeImageLoaderEngine.Builder()
            .apply { androidMappings.forEach { (url, bmp) -> intercept(url, bmp.asImage()) } }
            .build()
    val imageLoader = ImageLoader.Builder(context).components { add(engine) }.build()
    androidMappings.forEach { (url, bmp) ->
        imageLoader.memoryCache?.set(MemoryCache.Key(url), MemoryCache.Value(bmp.asImage()))
    }
    return CoilBitmapLoader(context = context, imageLoader = imageLoader)
}
