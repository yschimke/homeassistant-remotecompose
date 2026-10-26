package ee.schimke.terrazzo.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ee.schimke.terrazzo.dashboard.LiveCameras
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex

/**
 * A camera's live view: Home Assistant's MJPEG stream (`/api/camera_proxy_stream/…`) for the camera
 * whose picture is [pictureUrl] (its `entity_picture`, `/api/camera_proxy/…?token=…`), as decoded
 * frames no larger than [maxDimensionPx]. Home Assistant serves the stream for every camera: one
 * with its own MJPEG feed passes it through, any other gets its still pictures pushed as they
 * change.
 *
 * Frames a slow collector misses are dropped, never queued. The flow completes when Home Assistant
 * ends the stream and fails when it can't be opened (no such camera, an expired token, offline, a
 * demo picture no server answers): the caller falls back to still pictures.
 */
fun HaImageStack.cameraStream(pictureUrl: String, maxDimensionPx: Int): Flow<Bitmap> {
  val url = cameraStreamUrl(resolveAgainstBase(pictureUrl))
  return callbackFlow {
      if (url == null) throw IOException("No stream for this picture")
      val call = httpClient.newCall(Request.Builder().url(url).build())
      launch(Dispatchers.IO) {
        try {
          call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Stream answered ${response.code}")
            readMjpegFrames(response.body.source()) { trySend(it) }
          }
          close()
        } catch (e: IOException) {
          close(e)
        }
      }
      awaitClose { call.cancel() }
    }
    .conflate()
    .mapNotNull { decodeFrame(it, maxDimensionPx) }
    .flowOn(Dispatchers.Default)
}

/**
 * The MJPEG stream URL for a camera picture URL: `camera_proxy` becomes `camera_proxy_stream`,
 * keeping the access token, which the stream takes too, and dropping a [LiveCameras.FRAME_PARAM].
 * Null for anything else.
 */
internal fun cameraStreamUrl(pictureUrl: String): String? {
  val parsed = pictureUrl.toHttpUrlOrNull() ?: return null
  val segments = parsed.pathSegments
  if (segments.size < 3 || segments[0] != "api" || segments[1] != "camera_proxy") return null
  return parsed
    .newBuilder()
    .setPathSegment(1, "camera_proxy_stream")
    .removeAllQueryParameters(LiveCameras.FRAME_PARAM)
    .build()
    .toString()
}

/**
 * Hands each JPEG in a `multipart/x-mixed-replace` stream to [onFrame] until the stream ends.
 *
 * Read by the pictures themselves rather than by the multipart boundary, which a camera passing its
 * own feed through names as it likes: the bytes before a JPEG's start marker are the part's
 * headers, and its `Content-Length`, when there is one, says where the picture ends; without one
 * the picture runs to its end marker.
 */
internal fun readMjpegFrames(source: BufferedSource, onFrame: (ByteArray) -> Unit) {
  while (true) {
    val start = source.indexOf(JPEG_START, 0, MAX_HEADER_BYTES)
    if (start < 0) {
      // Ended, or so much before a picture that this isn't an MJPEG stream.
      if (!source.request(MAX_HEADER_BYTES)) return
      throw IOException("No picture in the stream")
    }
    val headers = source.readUtf8(start)
    val length = CONTENT_LENGTH.find(headers)?.groupValues?.get(1)?.toLongOrNull()
    val frame =
      if (length != null && length in JPEG_START.size..MAX_FRAME_BYTES) {
        source.readByteArray(length)
      } else {
        val end = source.indexOf(JPEG_END, JPEG_START.size.toLong(), MAX_FRAME_BYTES)
        if (end < 0) return
        source.readByteArray(end + JPEG_END.size)
      }
    onFrame(frame)
  }
}

/**
 * [jpeg] decoded, sampled down by powers of two to no more than [maxDimensionPx] a side; null when
 * it won't decode.
 */
private fun decodeFrame(jpeg: ByteArray, maxDimensionPx: Int): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimensionPx) sample *= 2
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
}

private val JPEG_START = "ffd8".decodeHex()
private val JPEG_END = "ffd9".decodeHex()
private val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")

/** The most a part's headers (and the boundary before them) run to. */
private const val MAX_HEADER_BYTES = 64L * 1024

/** The largest picture read; past it a frame is taken as broken. */
private const val MAX_FRAME_BYTES = 16L * 1024 * 1024
