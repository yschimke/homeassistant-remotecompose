package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ee.schimke.ha.model.EntityState
import ee.schimke.terrazzo.LocalHaImageStack
import ee.schimke.terrazzo.image.HaImageStack
import ee.schimke.terrazzo.image.cameraStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A camera's live view, leading its more-info sheet: Home Assistant's MJPEG stream while it plays,
 * else a fresh still every [STILL_INTERVAL_MS] (a demo camera, a stream that won't open), trying
 * the stream again every [STREAM_RETRY_MS]. It opens on the dashboard's own frame, already in the
 * image cache, so the sheet arrives showing a picture, and plays only while the screen is resumed.
 *
 * The stream is opened with the picture's token at the time and kept across Home Assistant's
 * five-minute token rotation; a reconnect takes the current one. Draws nothing without an image
 * stack (previews) or a picture.
 */
@Composable
internal fun LiveCameraFeed(entityId: String, state: EntityState?, modifier: Modifier = Modifier) {
  val imageStack = LocalHaImageStack.current ?: return
  val picture =
    (state?.attributes?.get("entity_picture") as? JsonPrimitive)?.contentOrNull?.takeIf {
      it.isNotBlank()
    } ?: return
  val currentPicture by rememberUpdatedState(picture)
  var frame by remember(entityId) { mutableStateOf<ImageBitmap?>(null) }
  var streaming by remember(entityId) { mutableStateOf(false) }
  var tried by remember(entityId) { mutableStateOf(false) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(entityId, imageStack) {
    imageStack.still(currentPicture, fresh = false)?.let { frame = it }
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        try {
          imageStack.cameraStream(currentPicture, MAX_DIMENSION_PX).collect {
            frame = it.asImageBitmap()
            streaming = true
          }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Falls back to stills below.
        }
        streaming = false
        repeat((STREAM_RETRY_MS / STILL_INTERVAL_MS).toInt()) {
          imageStack.still(currentPicture, fresh = true)?.let { frame = it }
          tried = true
          delay(STILL_INTERVAL_MS)
        }
      }
    }
  }

  val skin = currentScreenSkin()
  val shape = RoundedCornerShape(skin.controlCorner)
  val ratio = frame?.let { it.width.toFloat() / it.height }?.takeIf { it > 0f } ?: (16f / 9f)
  Box(
    modifier
      .fillMaxWidth()
      .aspectRatio(ratio)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
  ) {
    val current = frame
    when {
      current != null ->
        Image(
          current,
          contentDescription = "Camera picture",
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize(),
        )
      tried ->
        Text(
          "No picture from this camera",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.Center),
        )
      else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
    if (current != null) FeedBadge(streaming, Modifier.align(Alignment.TopStart))
  }
}

/** "Live" with a red dot while the stream plays, else how often the still refreshes. */
@Composable
private fun FeedBadge(streaming: Boolean, modifier: Modifier = Modifier) {
  val label = if (streaming) "Live" else "Every ${STILL_INTERVAL_MS / 1000} s"
  Row(
    modifier
      .padding(8.dp)
      .background(Color.Black.copy(alpha = 0.55f), CircleShape)
      .padding(horizontal = 10.dp, vertical = 4.dp)
      .semantics { contentDescription = if (streaming) "Live stream" else "Refreshing stills" },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (streaming) Box(Modifier.size(8.dp).background(Color(0xFFE53935), CircleShape))
    Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
  }
}

private suspend fun HaImageStack.still(picture: String, fresh: Boolean): ImageBitmap? =
  resolve(picture, fresh = fresh, maxDimensionPx = MAX_DIMENSION_PX)?.asImageBitmap()

/** Between stills when the stream won't play. */
private const val STILL_INTERVAL_MS = 1_000L

/** How long stills stand in before the stream is tried again. */
private const val STREAM_RETRY_MS = 30_000L

/** The largest side a frame is decoded at: a sheet's width on a large phone. */
private const val MAX_DIMENSION_PX = 1_280
