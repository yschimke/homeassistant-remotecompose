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
 * How often dashboard cameras get a fresh frame.
 *
 * Home Assistant only changes a camera's `entity_picture` when it rotates the access token, every
 * five minutes, so a dashboard camera would otherwise show a new frame that often. Every camera on
 * an open dashboard gets a fresh frame every [DASHBOARD_FRAME_INTERVAL_MS] instead, while the
 * dashboard is in the foreground; one the user asked to watch live, from a card's long-press sheet,
 * every [FRAME_INTERVAL_MS]. Each tick adds a [FRAME_PARAM] to the picture's URL, which the image
 * stack fetches network first (see `LiveImageCacheInterceptor`, which leaves the parameter out of
 * the disk key, so the disk still holds one frame per camera).
 *
 * A live camera is kept for the process only and ended after [MAX_LIVE_MS], so a camera left live
 * doesn't keep fetching frames, and so battery and data, for longer than the user is likely
 * watching; it falls back to the dashboard's own pace.
 */
object LiveCameras {
  /** Between live frames. */
  const val FRAME_INTERVAL_MS: Long = 2_000L

  /** Between frames of any other camera on an open dashboard. */
  const val DASHBOARD_FRAME_INTERVAL_MS: Long = 5_000L

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
 * [snapshot] with each of [cameras] (the open dashboard's) moved on to its current frame while the
 * screen is resumed: every [LiveCameras.DASHBOARD_FRAME_INTERVAL_MS], or every
 * [LiveCameras.FRAME_INTERVAL_MS] for a live one. The first frame is the snapshot's own picture,
 * which the dashboard prewarmed, so cards arrive showing it. With no camera, [snapshot] itself, and
 * no ticker.
 */
@Composable
internal fun rememberLiveCameraFrames(snapshot: HaSnapshot, cameras: Set<String>): HaSnapshot {
  val live by LiveCameras.live.collectAsState()
  if (cameras.isEmpty() && live.isEmpty()) return snapshot
  var dashboardFrame by remember { mutableLongStateOf(0L) }
  var liveFrame by remember { mutableLongStateOf(0L) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle, cameras.isNotEmpty()) {
    if (cameras.isEmpty()) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        delay(LiveCameras.DASHBOARD_FRAME_INTERVAL_MS)
        dashboardFrame++
      }
    }
  }
  LaunchedEffect(lifecycle, live.isNotEmpty()) {
    if (live.isEmpty()) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        delay(LiveCameras.FRAME_INTERVAL_MS)
        LiveCameras.expire()
        liveFrame++
      }
    }
  }
  return remember(snapshot, cameras, live.keys, dashboardFrame, liveFrame) {
    snapshot.withLiveFrames(cameraFrames(cameras, live.keys, dashboardFrame, liveFrame))
  }
}

/**
 * Each camera's frame token: live ones on [liveFrame] once it has ticked, the rest of [cameras] on
 * [dashboardFrame]. Before either ticks, a camera is left out, keeping the snapshot's own URL.
 */
internal fun cameraFrames(
  cameras: Set<String>,
  live: Set<String>,
  dashboardFrame: Long,
  liveFrame: Long,
): Map<String, String> = buildMap {
  if (dashboardFrame > 0) cameras.forEach { put(it, "d$dashboardFrame") }
  if (liveFrame > 0) live.forEach { put(it, "l$liveFrame") }
}

/**
 * Each of [frames]' cameras' `entity_picture` URL with a [LiveCameras.FRAME_PARAM] of its frame.
 */
internal fun HaSnapshot.withLiveFrames(frames: Map<String, String>): HaSnapshot {
  if (frames.isEmpty()) return this
  var changed = false
  val updated = states.mapValues { (id, entity) ->
    val frame = frames[id] ?: return@mapValues entity
    val url =
      (entity.attributes["entity_picture"] as? JsonPrimitive)?.contentOrNull
        ?: return@mapValues entity
    changed = true
    entity.copy(
      attributes =
        kotlinx.serialization.json.JsonObject(
          entity.attributes + ("entity_picture" to JsonPrimitive(withFrame(url, frame)))
        )
    )
  }
  return if (changed) copy(states = updated) else this
}

/** [url] with a [LiveCameras.FRAME_PARAM] of [frame]. */
internal fun withFrame(url: String, frame: String): String {
  val separator = if ('?' in url) '&' else '?'
  return "$url$separator${LiveCameras.FRAME_PARAM}=$frame"
}
