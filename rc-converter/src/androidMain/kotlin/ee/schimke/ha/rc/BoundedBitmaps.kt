package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The longest edge, in pixels, a card image is decoded at. Cards draw entity pictures, media art
 * and camera snapshots at a fraction of the screen; a 4K camera frame decoded whole costs ~32 MB
 * for no visible gain.
 */
internal const val CARD_IMAGE_MAX_PX = 1440

/**
 * Decodes [bytes] downsampled by a power of two until its longest edge is at most [maxPx]: a
 * bounds-only pass reads the size, then the real decode uses `inSampleSize`. Null for undecodable
 * input, like [BitmapFactory.decodeByteArray].
 */
internal fun decodeBoundedBitmap(bytes: ByteArray, maxPx: Int = CARD_IMAGE_MAX_PX): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, maxPx) }
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal fun sampleSizeFor(bounds: BitmapFactory.Options, maxPx: Int): Int {
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
  return sample
}
