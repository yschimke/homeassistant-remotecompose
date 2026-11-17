package ee.schimke.terrazzo.quest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rccreation.common.RcPlatformProfiles
import ee.schimke.composeai.rccreation.compose.action.hostAction
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.captureCommonRemoteDocument
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.state.rsp
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.room.RoomEntity
import ee.schimke.terrazzo.room.RoomContent
import ee.schimke.terrazzo.room.TOGGLE_DOMAINS
import ee.schimke.terrazzo.shared.RemoteComposeCard
import kotlinx.coroutines.CancellationException

internal fun RoomContent.deviceGroups(): Map<String, List<RoomEntity>> {
  val controls = state?.controls ?: return emptyMap()
  return (controls.primary + controls.more)
    .groupBy { snapshot?.devices?.get(it.entityId)?.deviceId ?: it.entityId }
    .mapValues { (_, members) -> members.sortedBy { it.entityId } }
}

private data class DeviceControl(val id: String, val name: String, val toggle: Boolean)

@Composable
internal fun QuestDeviceCard(
  entities: List<RoomEntity>,
  snapshot: HaSnapshot?,
  locked: Boolean,
  onToggle: (String) -> Unit,
  onPin: ((String) -> Unit)?,
  pinnedIds: Set<String>,
  onUnpin: (() -> Unit)? = null,
) {
  val controls = entities.map { DeviceControl(it.entityId, it.name, it.domain in TOGGLE_DOMAINS) }
  val canPin = onPin != null
  val canUnpin = onUnpin != null
  val height =
    24 + controls.sumOf { 80 + (if (it.toggle) 48 else 0) + (if (canPin || canUnpin) 48 else 0) }
  var bytes by remember(controls, locked, canPin, canUnpin) { mutableStateOf<ByteArray?>(null) }
  var failed by remember(controls, locked, canPin, canUnpin) { mutableStateOf(false) }
  LaunchedEffect(controls, locked, canPin, canUnpin) {
    try {
      bytes =
        captureCommonRemoteDocument(
          creationDisplayInfo = RemoteCreationDisplayInfo(360, height, 160, 1f),
          profile = RcPlatformProfiles.ANDROIDX,
        ) {
          DeviceDocument(controls, locked, canPin, canUnpin)
        }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      android.util.Log.e("QuestDeviceCard", "Unable to capture device controls", e)
      failed = true
    }
  }
  val bindings = buildMap {
    for (control in controls) {
      val state = snapshot?.states?.get(control.id)
      put(
        control.id + ".state",
        RcNamedValue.Text(state?.let { formatState(it, snapshot) } ?: "Unavailable"),
      )
      put(
        control.id + ".toggle",
        RcNamedValue.Text(
          when {
            locked -> "Controls locked"
            state == null || !HaLiveBindings.isAvailable(state.state) -> "Unavailable"
            HaLiveBindings.isOn(control.id, state.state) -> "Turn off"
            else -> "Turn on"
          },
        ),
      )
      put(
        control.id + ".pin",
        RcNamedValue.Text(if (control.id in pinnedIds) "Move here" else "Pin here"),
      )
    }
  }
  Box(Modifier.fillMaxSize().background(Color(0xFF24262A)).verticalScroll(rememberScrollState())) {
    val document = bytes
    if (document != null) {
      RemoteComposeCard(document, Modifier.fillMaxWidth().height(height.dp), bindings) { event ->
        if (event is RcPlayerEvent.HostNamedAction) {
          for (control in controls) {
            when (event.name) {
              "toggle/" + control.id ->
                if (!locked && control.toggle && snapshot?.states?.containsKey(control.id) == true)
                  onToggle(control.id)
              "pin/" + control.id -> onPin?.invoke(control.id)
              "unpin/" + control.id -> onUnpin?.invoke()
            }
          }
        }
      }
    } else {
      Text(if (failed) "Device card unavailable" else "Loading device card…", color = Color.White)
    }
  }
}

@Composable
@RemoteComposable
private fun DeviceDocument(
  controls: List<DeviceControl>,
  locked: Boolean,
  canPin: Boolean,
  canUnpin: Boolean,
) {
  RemoteColumn(RemoteModifier.fillMaxWidth().padding(12.rdp)) {
    for (control in controls) {
      RemoteText(control.name.rs, color = Color.White.rc, fontSize = 22.rsp, maxLines = 2)
      RemoteText(deviceSlot(control.id, "state"), color = Color.LightGray.rc, fontSize = 18.rsp)
      if (control.toggle) {
        DeviceAction(deviceSlot(control.id, "toggle"), if (locked) null else "toggle/" + control.id)
      }
      if (canPin) DeviceAction(deviceSlot(control.id, "pin"), "pin/" + control.id)
      if (canUnpin) DeviceAction("Unpin".rs, "unpin/" + control.id)
    }
  }
}

private fun deviceSlot(id: String, field: String) =
  RemoteString.createNamedRemoteString("$id.$field", "", RemoteState.Domain.User)

@Composable
@RemoteComposable
private fun DeviceAction(label: RemoteString, action: String?) {
  val modifier =
    if (action == null) RemoteModifier else RemoteModifier.clickable(hostAction(action.rs))
  RemoteText(
    label,
    modifier.fillMaxWidth().padding(10.rdp),
    color = Color(0xFFB2DBFF).rc,
    fontSize = 18.rsp,
  )
}
