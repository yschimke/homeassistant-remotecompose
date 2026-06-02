@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package ee.schimke.ha.rc.image

import android.content.ContextWrapper
import coil3.ComponentRegistry
import coil3.Image
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the memory-cache hit path is a direct synchronous lookup — no coroutine dispatch,
 * no thread switch, no calls into the Coil engine. The point of this loader is that it runs on the
 * RemoteCompose player's decode thread without ever blocking; if a future refactor reintroduces
 * `executeBlocking` / `runBlocking` / `withContext`, this test should fail.
 */
class CoilBitmapLoaderTest {

  @Test
  fun memoryHitReturnsBytesSynchronouslyFromCallingThread() {
    val name = "https://example.test/icon.png"
    val cachedImage = StubImage()
    val cache = RecordingMemoryCache(initial = mapOf(MemoryCache.Key(name) to cachedImage))
    val loader = StubImageLoader(memCache = cache)
    val warmUpCalls = mutableListOf<Pair<String, MemoryCache.Key>>()
    val sentinel = byteArrayOf(7, 8, 9)

    val coil =
      object : CoilBitmapLoader(context = NULL_CONTEXT, imageLoader = loader) {
        override fun encodeImage(image: Image): ByteArray? {
          return if (image === cachedImage) sentinel else null
        }

        override fun warmUpAsync(name: String, key: MemoryCache.Key) {
          warmUpCalls += name to key
        }
      }

    val callingThread = Thread.currentThread()
    val stream = coil.loadBitmap(name)
    val bytes = stream.readBytes()

    // Synchronous: the lookup ran on the calling thread (no coroutine
    // dispatch could have run a get() on a different thread).
    assertContentEquals(sentinel, bytes)
    assertEquals(listOf(MemoryCache.Key(name)), cache.getCalls)
    assertEquals(listOf(callingThread), cache.getThreads)
    // Hit path does not enqueue.
    assertTrue(warmUpCalls.isEmpty(), "warm-up should not run on a hit")
  }

  @Test
  fun memoryMissReturnsEmptyAndEnqueuesWarmUpWithSameKey() {
    val name = "https://example.test/missing.png"
    val cache = RecordingMemoryCache(initial = emptyMap())
    val loader = StubImageLoader(memCache = cache)
    val warmUpCalls = mutableListOf<Pair<String, MemoryCache.Key>>()

    val coil =
      object : CoilBitmapLoader(context = NULL_CONTEXT, imageLoader = loader) {
        override fun warmUpAsync(name: String, key: MemoryCache.Key) {
          warmUpCalls += name to key
        }
      }

    val stream = coil.loadBitmap(name)
    val bytes = stream.readBytes()

    // Empty stream → BitmapFactory.decodeStream → null bitmap → player keeps placeholder.
    assertEquals(0, bytes.size)
    // Both the lookup and the warm-up share the same explicit key.
    assertEquals(listOf(MemoryCache.Key(name)), cache.getCalls)
    assertEquals(listOf(name to MemoryCache.Key(name)), warmUpCalls)
  }

  @Test
  fun nonBitmapCachedImageFallsThroughToWarmUp() {
    // Coil can hold non-Bitmap Images (e.g. ColorImage). The default
    // encodeImage returns null for those; the loader should treat that
    // like a miss rather than crashing.
    val name = "schemed://x"
    val nonBitmap = StubImage()
    val cache = RecordingMemoryCache(initial = mapOf(MemoryCache.Key(name) to nonBitmap))
    val loader = StubImageLoader(memCache = cache)
    val warmUpCalls = mutableListOf<Pair<String, MemoryCache.Key>>()

    val coil =
      object : CoilBitmapLoader(context = NULL_CONTEXT, imageLoader = loader) {
        override fun warmUpAsync(name: String, key: MemoryCache.Key) {
          warmUpCalls += name to key
        }
      }

    val stream = coil.loadBitmap(name)

    assertEquals(0, stream.readBytes().size)
    assertEquals(1, warmUpCalls.size)
  }

  @Test
  fun customKeyAlignsLookupAndWarmUp() {
    val name = "logo"
    val customKey = MemoryCache.Key("scoped:$name")
    val cache = RecordingMemoryCache(initial = emptyMap())
    val loader = StubImageLoader(memCache = cache)
    val warmUpKeys = mutableListOf<MemoryCache.Key>()

    val coil =
      object : CoilBitmapLoader(context = NULL_CONTEXT, imageLoader = loader) {
        override fun memoryCacheKeyFor(name: String): MemoryCache.Key = customKey

        override fun warmUpAsync(name: String, key: MemoryCache.Key) {
          warmUpKeys += key
        }
      }

    coil.loadBitmap(name)

    // The exact custom key is used on both sides.
    assertEquals(listOf(customKey), cache.getCalls)
    assertEquals(listOf(customKey), warmUpKeys)
  }

  @Test
  fun relativeNameResolvesAgainstBaseUrlForLookupAndWarmUp() {
    // Home Assistant's `entity_picture` is path-only —
    // `/api/camera_proxy/...?token=...`. Coil's HttpUriFetcher
    // can't resolve a path-only URI; without resolution the
    // memory-cache lookup misses every call and the warm-up
    // silently errors. With baseUrl wired, both sides see the
    // absolute URL.
    val baseUrl = "https://ha.example.test"
    val relative = "/api/camera_proxy/camera.front?token=abc"
    val absolute = "$baseUrl$relative"
    val cache = RecordingMemoryCache(initial = emptyMap())
    val loader = StubImageLoader(memCache = cache)
    val warmUpCalls = mutableListOf<Pair<String, MemoryCache.Key>>()

    val coil =
      object : CoilBitmapLoader(context = NULL_CONTEXT, imageLoader = loader, baseUrl = baseUrl) {
        override fun warmUpAsync(name: String, key: MemoryCache.Key) {
          warmUpCalls += name to key
        }
      }

    coil.loadBitmap(relative)

    assertEquals(listOf(MemoryCache.Key(absolute)), cache.getCalls)
    assertEquals(listOf(absolute to MemoryCache.Key(absolute)), warmUpCalls)
  }

  @Test
  fun absoluteAndSchemedNamesPassThroughEvenWithBaseUrl() {
    // Anything that isn't a relative server path is forwarded
    // unchanged: HTTPS, content://, file://, android.resource://,
    // and untyped non-slash names (e.g. registry-style "logo").
    val baseUrl = "https://ha.example.test/"
    val cache = RecordingMemoryCache(initial = emptyMap())
    val loader = StubImageLoader(memCache = cache)
    val warmUpNames = mutableListOf<String>()

    val coil =
      object : CoilBitmapLoader(context = NULL_CONTEXT, imageLoader = loader, baseUrl = baseUrl) {
        override fun warmUpAsync(name: String, key: MemoryCache.Key) {
          warmUpNames += name
        }
      }

    listOf(
        "https://cdn.example.test/icon.png",
        "content://media/external/images/42",
        "file:///data/local/tmp/img.png",
        "android.resource://ee.schimke.terrazzo/2131099648",
        "logo",
      )
      .forEach { coil.loadBitmap(it) }

    assertEquals(
      listOf(
        "https://cdn.example.test/icon.png",
        "content://media/external/images/42",
        "file:///data/local/tmp/img.png",
        "android.resource://ee.schimke.terrazzo/2131099648",
        "logo",
      ),
      warmUpNames,
    )
  }

  @Test
  fun trailingSlashOnBaseUrlIsTrimmedSoResolutionHasOneSeparator() {
    val cache = RecordingMemoryCache(initial = emptyMap())
    val loader = StubImageLoader(memCache = cache)
    val warmUpNames = mutableListOf<String>()

    val coil =
      object :
        CoilBitmapLoader(
          context = NULL_CONTEXT,
          imageLoader = loader,
          baseUrl = "https://ha.example.test/",
        ) {
        override fun warmUpAsync(name: String, key: MemoryCache.Key) {
          warmUpNames += name
        }
      }

    coil.loadBitmap("/api/image_proxy/foo")

    assertEquals(listOf("https://ha.example.test/api/image_proxy/foo"), warmUpNames)
  }

  @Test
  fun nullBaseUrlLeavesRelativeNameUntouched() {
    // Back-compat path: when no baseUrl is wired (preview / tests
    // that haven't been updated), relative names still flow
    // through. The fetch will fail like before — this just
    // confirms we didn't change the legacy behaviour.
    val name = "/api/camera_proxy/camera.x"
    val cache = RecordingMemoryCache(initial = emptyMap())
    val loader = StubImageLoader(memCache = cache)
    val warmUpNames = mutableListOf<String>()

    val coil =
      object : CoilBitmapLoader(context = NULL_CONTEXT, imageLoader = loader) {
        override fun warmUpAsync(name: String, key: MemoryCache.Key) {
          warmUpNames += name
        }
      }

    coil.loadBitmap(name)

    assertEquals(listOf(MemoryCache.Key(name)), cache.getCalls)
    assertEquals(listOf(name), warmUpNames)
  }
}

private val NULL_CONTEXT = ContextWrapper(null)

private class StubImage : Image {
  override val size: Long = 0L
  override val width: Int = 1
  override val height: Int = 1
  override val shareable: Boolean = false

  override fun draw(canvas: android.graphics.Canvas) = Unit
}

private class RecordingMemoryCache(initial: Map<MemoryCache.Key, Image>) : MemoryCache {
  private val store = initial.mapValues { (_, v) -> MemoryCache.Value(v) }.toMutableMap()
  val getCalls = mutableListOf<MemoryCache.Key>()
  val getThreads = mutableListOf<Thread>()

  override val size: Long = 0L
  override var maxSize: Long = Long.MAX_VALUE
  override val initialMaxSize: Long = Long.MAX_VALUE
  override val keys: Set<MemoryCache.Key>
    get() = store.keys

  override fun get(key: MemoryCache.Key): MemoryCache.Value? {
    getCalls += key
    getThreads += Thread.currentThread()
    return store[key]
  }

  override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
    store[key] = value
  }

  override fun remove(key: MemoryCache.Key): Boolean = store.remove(key) != null

  override fun trimToSize(size: Long) {}

  override fun clear() = store.clear()
}

private class StubImageLoader(memCache: MemoryCache) : ImageLoader {
  override val memoryCache: MemoryCache? = memCache
  override val diskCache: DiskCache? = null
  override val defaults: ImageRequest.Defaults
    get() = unsupported("defaults")

  override val components: ComponentRegistry
    get() = unsupported("components")

  override fun enqueue(request: ImageRequest): Disposable = unsupported("enqueue")

  override suspend fun execute(request: ImageRequest): ImageResult = unsupported("execute")

  override fun shutdown() = unsupported("shutdown")

  override fun newBuilder(): ImageLoader.Builder = unsupported("newBuilder")

  private fun unsupported(method: String): Nothing =
    error("StubImageLoader.$method must not be called from CoilBitmapLoader's hit/miss path")
}
