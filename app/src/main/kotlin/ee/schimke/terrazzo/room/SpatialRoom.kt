package ee.schimke.terrazzo.room

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.platform.requestFullSpace
import androidx.xr.compose.platform.requestHomeSpace
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialCurvedRow
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SpatialArrangement
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import kotlinx.coroutines.launch

/**
 * Chooses the room's layout for the device. In an XR headset's full space, [spatial] (with a way
 * back to home space); anywhere else, [flat], given a way into full space when the device is a
 * headset and null otherwise. Phones and tablets never see the XR path: without the XR spatial
 * feature both checks read false and the spatial libraries stay idle.
 */
@Composable
internal fun SpatialRoomHost(
  spatial: @Composable (onCollapse: () -> Unit) -> Unit,
  flat: @Composable (onEnterSpace: (() -> Unit)?) -> Unit,
) {
  val activity = LocalActivity.current as? ComponentActivity
  val scope = rememberCoroutineScope()
  if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
    spatial { activity?.let { scope.launch { it.requestHomeSpace() } } }
  } else {
    val enter: (() -> Unit)? =
      activity
        ?.takeIf { LocalSpatialConfiguration.current.hasXrSpatialFeature }
        ?.let { a -> { scope.launch { a.requestFullSpace() } } }
    flat(enter)
  }
}

/** The headset's way into full space, from the flat layout's top bar. */
@Composable
internal fun SurroundButton(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(Icons.Filled.OpenInFull, contentDescription = "Surround me with this room")
  }
}

/**
 * The room around the user. A curved row at arm's length: the room panel on the left (where the
 * user is, the rooms to switch to, the controls no dashboard shows), then a panel per dashboard
 * control, the most important nearest the centre of view. Every panel can be moved; the room panel
 * resized too, as its list grows. An orbiter under the room panel goes back or collapses to home
 * space.
 *
 * Each control can be pinned to the spot ahead of the user ("Pin here"): it then stays there, on a
 * persisted anchor, across visits ([PinnedPanels]), and a pin found nearby says which room the user
 * is in.
 */
@Composable
internal fun SpatialRoomControls(
  content: RoomContent,
  pinning: RoomPinning,
  onBack: () -> Unit,
  onCollapse: () -> Unit,
) {
  val controls = content.state?.controls
  val anchors = rememberPinAnchors()
  val actions: PinActions? = anchors?.let {
    rememberPinning(it, pinning.pins, pinning.onPinned, pinning.onMoved, pinning.onNearestPin)
  }
  val unpin: (RoomPin) -> Unit = { p ->
    anchors?.unpin(p.anchorId)
    pinning.onUnpinned(p)
  }
  if (anchors != null && actions != null) {
    PinnedPanels(anchors, pinning.pins, content, onMove = actions.move, onUnpin = unpin)
  }
  val lost = anchors?.let { a -> pinning.pins.filter { it.anchorId in a.lost } }.orEmpty()
  Subspace {
    SpatialCurvedRow(
      curveRadius = CURVE_RADIUS,
      horizontalArrangement = SpatialArrangement.spacedBy(PANEL_GAP),
    ) {
      SpatialPanel(
        SubspaceModifier.width(ROOM_PANEL_WIDTH).height(PANEL_HEIGHT).movable().resizable()
      ) {
        RoomPanel(content, lost, onRemoveLost = unpin)
        Orbiter(position = OrbiterPosition.BottomCenter()) {
          SpatialRoomActions(onBack = onBack, onCollapse = onCollapse)
        }
      }
      controls?.primary?.forEach { entity ->
        SpatialPanel(SubspaceModifier.width(CONTROL_PANEL_WIDTH).height(PANEL_HEIGHT).movable()) {
          Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
              RoomEntityCard(entity, content)
              actions?.let { PinHereButton { it.pin(entity.entityId) } }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RoomPanel(content: RoomContent, lost: List<RoomPin>, onRemoveLost: (RoomPin) -> Unit) {
  val controls = content.state?.controls
  Surface(Modifier.fillMaxSize()) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(controls?.area?.name ?: "This room", style = MaterialTheme.typography.headlineMedium)
      RoomHeader(content)
      LostPins(lost, content, onRemoveLost)
      if (controls == null || controls.isEmpty) {
        Text("Nothing to control here.", style = MaterialTheme.typography.bodyLarge)
      } else if (controls.more.isNotEmpty()) {
        Text("More in ${controls.area.name}", style = MaterialTheme.typography.titleMedium)
        controls.more.forEach { RoomEntityCard(it, content) }
      }
    }
  }
}

@Composable
private fun SpatialRoomActions(onBack: () -> Unit, onCollapse: () -> Unit) {
  Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp) {
    Row(
      Modifier.padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalIconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to dashboards")
      }
      FilledTonalIconButton(onClick = onCollapse) {
        Icon(Icons.Filled.CloseFullscreen, contentDescription = "Back to home space")
      }
    }
  }
}

private val CURVE_RADIUS = 1100.dp
private val PANEL_GAP = 32.dp
private val PANEL_HEIGHT = 560.dp
private val ROOM_PANEL_WIDTH = 440.dp
private val CONTROL_PANEL_WIDTH = 360.dp
