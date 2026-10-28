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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
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
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import ee.schimke.ha.room.RoomEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The headset's side of pinned controls: the session configured for persisted anchors and the
 * device's pose, each pin's anchor once loaded, and the pins whose place the headset couldn't load.
 *
 * A pin's place is an ARCore for XR anchor persisted to the headset's local storage. Loading one
 * gives its pose in the room, which [PinnedPanels] puts a panel on; an anchor that doesn't load
 * (the room changed, the headset forgot its spaces) is [lost], for the user to remove or pin again.
 */
@Stable
internal class PinAnchors(private val session: Session) {
  /** Whether the session runs with anchor persistence and device tracking. */
  var configured by mutableStateOf(false)
    private set

  val anchors = mutableStateMapOf<String, Anchor>()
  val lost = mutableStateListOf<String>()

  /** Turns on what pins need. False when the session refuses (missing permissions, no runtime). */
  fun configure(): Boolean {
    if (configured) return true
    configured =
      try {
        val config =
          Config.Builder(session.config)
            .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
            .setDeviceTracking(DeviceTrackingMode.SPATIAL)
            .build()
        session.configure(config) is SessionConfigureSuccess
      } catch (e: RuntimeException) {
        // SecurityException without the permissions; UnsupportedOperationException off a headset.
        false
      }
    return configured
  }

  /** Loads the anchor of each of [pins] not loaded or lost yet. */
  fun load(pins: List<RoomPin>) {
    if (!configured) return
    for (pin in pins) {
      if (pin.anchorId in anchors || pin.anchorId in lost) continue
      val result =
        try {
          Anchor.load(session, UUID.fromString(pin.anchorId))
        } catch (e: RuntimeException) {
          null
        }
      if (result is AnchorCreateSuccess) anchors[pin.anchorId] = result.anchor
      else lost += pin.anchorId
    }
  }

  /**
   * A new anchor [PIN_DISTANCE_METRES] ahead of where the user looks, persisted: the place a
   * control is pinned to. Its id, or null when the headset can't place or keep one right now.
   */
  suspend fun pinAhead(): String? {
    if (!configure()) return null
    return try {
      val device = ArDevice.getInstance(session).state.value
      if (device.trackingState != TrackingState.TRACKING) return null
      // Facing the way the user looks, so the panel on it faces back at them.
      val pose = device.devicePose compose Pose(Vector3(0f, 0f, -PIN_DISTANCE_METRES))
      val anchor = (Anchor.create(session, pose) as? AnchorCreateSuccess)?.anchor ?: return null
      val id = anchor.persist().toString()
      anchors[id] = anchor
      id
    } catch (e: CancellationException) {
      throw e
    } catch (e: RuntimeException) {
      null
    }
  }

  /** Forgets [anchorId]'s place, on the headset as well. */
  fun unpin(anchorId: String) {
    anchors.remove(anchorId)?.detach()
    lost -= anchorId
    try {
      Anchor.unpersist(session, UUID.fromString(anchorId))
    } catch (e: RuntimeException) {
      // Already gone from the headset's storage: nothing left to forget.
    }
  }

  /** Each tracked anchor's distance from the user, in metres. */
  fun distances(): Map<String, Float> {
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
 * Pinning for the spatial room: configures [anchors] and loads [pins] when the permissions are
 * already granted, keeps [onNearestPin] told which pinned entity the user stands nearest, and
 * returns the action that pins an entity ahead of the user (asking for the permissions first, the
 * first time).
 */
@Composable
internal fun rememberPinning(
  anchors: PinAnchors,
  pins: List<RoomPin>,
  onPinned: (anchorId: String, entityId: String) -> Unit,
  onNearestPin: (String?) -> Unit,
): (entityId: String) -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var pending by remember { mutableStateOf<String?>(null) }
  fun granted() = PIN_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
  }
  fun pinNow(entityId: String) {
    scope.launch { anchors.pinAhead()?.let { onPinned(it, entityId) } }
  }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result
      ->
      val entity = pending
      pending = null
      if (entity != null && result.values.all { it }) pinNow(entity)
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
  return { entityId ->
    if (granted()) pinNow(entityId)
    else {
      pending = entityId
      launcher.launch(PIN_PERMISSIONS)
    }
  }
}

/**
 * A panel on each of [pins] whose place the headset found: the entity's controls, where the user
 * left them, and a way to unpin it.
 */
@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
internal fun PinnedPanels(
  anchors: PinAnchors,
  pins: List<RoomPin>,
  content: RoomContent,
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
              RoomEntityCard(pinnedEntity(pin, content), content)
              TextButton(onClick = { onUnpin(pin) }) { Text("Unpin") }
            }
          }
        }
      }
    }
  }
}

/** A control panel's "Pin here": pins the entity's controls to the spot ahead of the user. */
@Composable
internal fun PinHereButton(onClick: () -> Unit) {
  TextButton(onClick = onClick) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(Icons.Filled.PushPin, contentDescription = null)
      Text("Pin here")
    }
  }
}

/** The pins whose place the headset couldn't load, each with a way to remove it. */
@Composable
internal fun LostPins(lost: List<RoomPin>, content: RoomContent, onRemove: (RoomPin) -> Unit) {
  if (lost.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Pins not found here", style = MaterialTheme.typography.titleMedium)
    Text(
      "The headset couldn't find where these were pinned. Remove them and pin them again.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (pin in lost) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pinnedEntity(pin, content).name, Modifier.weight(1f))
        TextButton(onClick = { onRemove(pin) }) { Text("Remove") }
      }
    }
  }
}

private fun pinnedEntity(pin: RoomPin, content: RoomContent): RoomEntity {
  val state = content.snapshot?.states?.get(pin.entityId)
  val name =
    (state?.attributes?.get("friendly_name") as? kotlinx.serialization.json.JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content ?: pin.entityId
  return RoomEntity(pin.entityId, name, importance = 0f, active = state?.state == "on")
}

/** Far enough to read at a glance, near enough to reach: about arm's length past the hand. */
private const val PIN_DISTANCE_METRES = 0.8f
private const val NEAREST_PIN_INTERVAL_MS = 2_000L
private val PINNED_PANEL_WIDTH = 340.dp
private val PINNED_PANEL_HEIGHT = 420.dp
