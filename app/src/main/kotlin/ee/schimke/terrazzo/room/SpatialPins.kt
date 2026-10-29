package ee.schimke.terrazzo.room

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Plane
import androidx.xr.arcore.PlaneType
import androidx.xr.arcore.TrackingState
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.animation.follow.FollowTarget
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.AnchorPersistenceMode
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android XR's [RoomAnchors]: the session configured for persisted anchors, the device's pose and
 * plane tracking, each pin's anchor once loaded, and the pins whose place the headset couldn't
 * load. Where a new pin goes is the shared [pinPlacement]; this class only converts ARCore for XR's
 * poses and planes to and from it.
 *
 * A pin's place is an ARCore for XR anchor persisted to the headset's local storage. Loading one
 * gives its pose in the room, which [PinnedPanels] puts a panel on; an anchor that doesn't load
 * (the room changed, the headset forgot its spaces) is [lost], for the user to remove or pin again.
 */
@Stable
internal class PinAnchors(private val session: Session) : RoomAnchors {
  /** Whether the session runs with anchor persistence and device tracking. */
  var configured by mutableStateOf(false)
    private set

  val anchors = mutableStateMapOf<String, Anchor>()
  private val lostIds = mutableStateListOf<String>()

  override val placed: Set<String>
    get() = anchors.keys.toSet()

  override val lost: Set<String>
    get() = lostIds.toSet()

  override fun configure(): Boolean {
    if (configured) return true
    configured =
      try {
        val config =
          Config.Builder(session.config)
            .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
            .setDeviceTracking(DeviceTrackingMode.SPATIAL)
            // Walls to pin controls onto ([pinPlacement]).
            .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)
            .build()
        session.configure(config) is SessionConfigureSuccess
      } catch (e: RuntimeException) {
        // SecurityException without the permissions; UnsupportedOperationException off a headset.
        false
      }
    return configured
  }

  override fun load(pins: List<RoomPin>) {
    if (!configured) return
    for (pin in pins) {
      if (pin.anchorId in anchors || pin.anchorId in lostIds) continue
      val result =
        try {
          Anchor.load(session, UUID.fromString(pin.anchorId))
        } catch (e: RuntimeException) {
          null
        }
      if (result is AnchorCreateSuccess) anchors[pin.anchorId] = result.anchor
      else lostIds += pin.anchorId
    }
  }

  override suspend fun pinWhereLooking(): PinPlace? {
    if (!configure()) return null
    return try {
      val device = ArDevice.getInstance(session).state.value
      if (device.trackingState != TrackingState.TRACKING) return null
      val head = device.devicePose
      val placement = pinPlacement(head.translation.toVec3(), head.forward.toVec3(), trackedWalls())
      // A panel shows its front along +Z: point that where the placement faces, upright.
      val pose =
        Pose(
          placement.position.toVector3(),
          Quaternion.fromLookTowards(placement.facing.toVector3(), Vector3.Up),
        )
      val anchor = (Anchor.create(session, pose) as? AnchorCreateSuccess)?.anchor ?: return null
      val id = anchor.persist().toString()
      anchors[id] = anchor
      PinPlace(id, onWall = placement.onWall)
    } catch (e: CancellationException) {
      throw e
    } catch (e: RuntimeException) {
      null
    }
  }

  /** The walls the headset tracks now; none when plane tracking is off or refused. */
  private fun trackedWalls(): List<WallPlane> =
    try {
      Plane.subscribe(session).value.mapNotNull { plane ->
        if (plane.type != PlaneType.VERTICAL) return@mapNotNull null
        val state = plane.state.value
        if (state.trackingState != TrackingState.TRACKING || state.subsumedBy != null) {
          return@mapNotNull null
        }
        // ARCore for XR: +Y is the plane's normal; its extents run along its X and Z.
        val center = state.centerPose
        WallPlane(
          center = center.translation.toVec3(),
          normal = center.up.toVec3(),
          widthAxis = center.right.toVec3(),
          heightAxis = center.backward.toVec3(),
          width = state.extents.width,
          height = state.extents.height,
        )
      }
    } catch (e: RuntimeException) {
      emptyList()
    }

  override fun unpin(anchorId: String) {
    anchors.remove(anchorId)?.detach()
    lostIds -= anchorId
    try {
      Anchor.unpersist(session, UUID.fromString(anchorId))
    } catch (e: RuntimeException) {
      // Already gone from the headset's storage: nothing left to forget.
    }
  }

  override fun distances(): Map<String, Float> {
    if (!configured) return emptyMap()
    val device =
      try {
        ArDevice.getInstance(session).state.value
      } catch (e: RuntimeException) {
        return emptyMap()
      }
    if (device.trackingState != TrackingState.TRACKING) return emptyMap()
    return anchors
      .filterValues { it.state.value.trackingState == TrackingState.TRACKING }
      .mapValues { (_, anchor) ->
        Vector3.distance(anchor.state.value.pose.translation, device.devicePose.translation)
      }
  }
}

private fun Vector3.toVec3() = Vec3(x, y, z)

private fun Vec3.toVector3() = Vector3(x, y, z)

/** The session's [PinAnchors], or null off a headset (no XR session). */
@Composable
internal fun rememberPinAnchors(): PinAnchors? {
  val session = LocalSession.current ?: return null
  return remember(session) { PinAnchors(session) }
}

/** The permissions anchors and the device's pose need on Android XR. */
internal val PIN_PERMISSIONS =
  arrayOf("android.permission.SCENE_UNDERSTANDING_COARSE", "android.permission.HEAD_TRACKING")

/**
 * What the spatial room can do with pins: [pin] an entity's controls where the user looks, or
 * [move] a pin there. Either lands on the wall the user looks at when one is in reach.
 */
internal class PinActions(val pin: (entityId: String) -> Unit, val move: (RoomPin) -> Unit)

/**
 * Pinning for the spatial room: configures [anchors] and loads [pins] when the permissions are
 * already granted, keeps [onNearestPin] told which pinned entity the user stands nearest, and
 * returns the [PinActions] (which ask for the permissions first, the first time). A new pin goes to
 * [onPinned]; a moved one to [onMoved] with its new anchor, its old one forgotten.
 */
@Composable
internal fun rememberPinning(
  anchors: RoomAnchors,
  pins: List<RoomPin>,
  onPinned: (anchorId: String, entityId: String) -> Unit,
  onMoved: (old: RoomPin, anchorId: String) -> Unit,
  onNearestPin: (String?) -> Unit,
): PinActions {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // The actions below are remembered; read the callbacks fresh so they never act on a stale caller.
  val pinned by rememberUpdatedState(onPinned)
  val moved by rememberUpdatedState(onMoved)
  // The entity to pin, and the pin it replaces when moving, while the permissions are asked for.
  var pending by remember { mutableStateOf<Pair<String, RoomPin?>?>(null) }
  fun granted() = PIN_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
  }
  fun pinNow(entityId: String, replacing: RoomPin?) {
    scope.launch {
      val place = anchors.pinWhereLooking() ?: return@launch
      if (replacing == null) pinned(place.anchorId, entityId)
      else {
        anchors.unpin(replacing.anchorId)
        moved(replacing, place.anchorId)
      }
    }
  }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result
      ->
      val request = pending
      pending = null
      if (request != null && result.values.all { it }) pinNow(request.first, request.second)
    }
  fun request(entityId: String, replacing: RoomPin?) {
    if (granted()) pinNow(entityId, replacing)
    else {
      pending = entityId to replacing
      launcher.launch(PIN_PERMISSIONS)
    }
  }

  LaunchedEffect(anchors, pins) {
    if (pins.isNotEmpty() && granted() && anchors.configure()) anchors.load(pins)
  }
  // Where the user stands moves slowly next to a frame: every couple of seconds is enough.
  LaunchedEffect(anchors, pins) {
    while (true) {
      val distances = anchors.distances()
      onNearestPin(
        nearestPinnedEntity(
          pins.mapNotNull { pin -> distances[pin.anchorId]?.let { pin.entityId to it } }
        )
      )
      delay(NEAREST_PIN_INTERVAL_MS)
    }
  }
  return remember(anchors) {
    PinActions(pin = { request(it, null) }, move = { request(it.entityId, it) })
  }
}

/**
 * A panel on each of [pins] whose place the headset found: the entity's controls, where the user
 * left them, with ways to move it to where the user now looks ([onMove]) or unpin it.
 */
@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
internal fun PinnedPanels(
  anchors: PinAnchors,
  pins: List<RoomPin>,
  content: RoomContent,
  onMove: (RoomPin) -> Unit,
  onUnpin: (RoomPin) -> Unit,
) {
  for (pin in pins) {
    val anchor = anchors.anchors[pin.anchorId] ?: continue
    key(pin.anchorId) {
      Subspace(follow = FollowTarget.anchor(anchor)) {
        SpatialPanel(SubspaceModifier.width(PINNED_PANEL_WIDTH).height(PINNED_PANEL_HEIGHT)) {
          Surface(Modifier.fillMaxSize()) {
            Column(
              Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              content.entityCard(pinnedEntity(pin, content.snapshot), Modifier)
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onMove(pin) }) { Text("Move here") }
                TextButton(onClick = { onUnpin(pin) }) { Text("Unpin") }
              }
            }
          }
        }
      }
    }
  }
}

private const val NEAREST_PIN_INTERVAL_MS = 2_000L
private val PINNED_PANEL_WIDTH = 340.dp
private val PINNED_PANEL_HEIGHT = 420.dp
