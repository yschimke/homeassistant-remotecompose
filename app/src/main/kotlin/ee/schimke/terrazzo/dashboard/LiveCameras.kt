package ee.schimke.terrazzo.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.core.logs.referencedEntities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Cameras the user asked to watch live, from a card's long-press sheet.
 *
 * Home Assistant only changes a camera's `entity_picture` when it rotates the access token, every
 * five minutes, so a dashboard camera otherwise shows a new frame that often. A live camera gets a
 * fresh frame every [FRAME_INTERVAL_MS] instead, while the dashboard is in the foreground: each
 * tick adds a [FRAME_PARAM] to the picture's URL, which the image stack fetches network first (see
 * `LiveImageCacheInterceptor`, which leaves the parameter out of the disk key, so the disk still
 * holds one frame per camera).
 *
 * Kept for the process only and ended after [MAX_LIVE_MS], so a camera left live doesn't keep
 * fetching frames, and so battery and data, for longer than the user is likely watching.
 */
object LiveCameras {
  /** Between live frames. */
  const val FRAME_INTERVAL_MS: Long = 2_000L

  /** How long a camera stays live once turned on. */
  const val MAX_LIVE_MS: Long = 10 * 60_000L

  /** The query parameter a live frame adds to the picture URL; Home Assistant ignores it. */
  const val FRAME_PARAM: String = "terrazzo_frame"

  private val since = MutableStateFlow<Map<String, Long>>(emptyMap())

  /** Live camera entity ids, each with when it was turned on. */
  val live: StateFlow<Map<String, Long>> = since.asStateFlow()

  fun setLive(
    entityIds: Collection<String>,
    on: Boolean,
    nowMs: Long = System.currentTimeMillis(),
  ) {
    since.update { current ->
      if (on) current + entityIds.associateWith { nowMs } else current - entityIds.toSet()
    }
  }

  /** Ends the cameras live for longer than [MAX_LIVE_MS]. */
  fun expire(nowMs: Long = System.currentTimeMillis()) {
    since.update { current -> current.filterValues { nowMs - it < MAX_LIVE_MS } }
  }
}

/** The camera entities [card] shows: what its "Live camera" switch turns on. */
fun cameraEntities(card: CardConfig): Set<String> =
  referencedEntities(card).filterTo(mutableSetOf()) { it.startsWith("camera.") }

/**
 * [snapshot] with each live camera's picture URL moved on to the current frame, ticking every
 * [LiveCameras.FRAME_INTERVAL_MS] while the screen is resumed. With no live camera, [snapshot]
 * itself, and no ticker.
 */
@Composable
internal fun rememberLiveCameraFrames(snapshot: HaSnapshot): HaSnapshot {
  val live by LiveCameras.live.collectAsState()
  if (live.isEmpty()) return snapshot
  var frame by remember { mutableLongStateOf(0L) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        delay(LiveCameras.FRAME_INTERVAL_MS)
        LiveCameras.expire()
        frame++
      }
    }
  }
  return remember(snapshot, live.keys, frame) { snapshot.withLiveFrames(live.keys, frame) }
}

/** [entityIds]' `entity_picture` URLs with a [LiveCameras.FRAME_PARAM] of [frame]. */
internal fun HaSnapshot.withLiveFrames(entityIds: Set<String>, frame: Long): HaSnapshot {
  var changed = false
  val updated = states.mapValues { (id, entity) ->
    if (id !in entityIds) return@mapValues entity
    val url =
      (entity.attributes["entity_picture"] as? JsonPrimitive)?.contentOrNull
        ?: return@mapValues entity
    changed = true
    val separator = if ('?' in url) '&' else '?'
    entity.copy(
      attributes =
        kotlinx.serialization.json.JsonObject(
          entity.attributes +
            ("entity_picture" to JsonPrimitive("$url$separator${LiveCameras.FRAME_PARAM}=$frame"))
        )
    )
  }
  return if (changed) copy(states = updated) else this
}
