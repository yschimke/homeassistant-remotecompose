package ee.schimke.terrazzo.previews

import coil3.Image
import coil3.asImage
import coil3.memory.MemoryCache
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.session.DemoData
import ee.schimke.terrazzo.image.DemoImages
import ee.schimke.terrazzo.image.HaImageStack
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Draws demo mode's pictures for [snapshot] into [stack]'s memory cache now, on the calling thread,
 * so a preview's first frame finds them, and returns them.
 *
 * Without this a picture card's first lookup misses and the picture arrives through Coil's async
 * pipeline ([ee.schimke.terrazzo.image.DemoImageFetcher] on a background dispatcher): whether it
 * lands before the render captures is a race, so a camera tile came out either drawn or as a flat
 * grey placeholder from one render to the next.
 *
 * Keyed as `CoilBitmapLoader` looks pictures up: `MemoryCache.Key` of the `entity_picture` resolved
 * against the demo session's [DemoData.BASE_URL] (its `memoryCacheKeyFor`, used by both the Compose
 * player's first-frame `cached` lookup and the AndroidX player's `loadBitmap`). The pixels are the
 * same [DemoImages.render] the fetcher draws, so a drawn tile looks as it did before.
 *
 * The caller holds the returned images for as long as the stack is used: the memory cache is sized
 * from the device's memory class and need not fit every picture, and Coil keeps an entry it evicts
 * in its weak cache only while something still references the image.
 */
internal fun prewarmDemoPictures(stack: HaImageStack, snapshot: HaSnapshot): List<Image> {
  val cache = stack.imageLoader.memoryCache ?: return emptyList()
  return demoPictureUrls(snapshot).mapNotNull { url ->
    val image = DemoImages.render(url)?.asImage() ?: return@mapNotNull null
    cache[MemoryCache.Key(url)] = MemoryCache.Value(image)
    image
  }
}

/** Each demo-host `entity_picture` in [snapshot], resolved against [DemoData.BASE_URL]. */
internal fun demoPictureUrls(snapshot: HaSnapshot): Set<String> =
  snapshot.states.values.mapNotNullTo(linkedSetOf()) { entity ->
    (entity.attributes["entity_picture"] as? JsonPrimitive)
      ?.contentOrNull
      ?.takeIf { it.startsWith('/') }
      ?.let { DemoData.BASE_URL.trimEnd('/') + it }
      ?.takeIf(DemoImages::isDemoUrl)
  }
