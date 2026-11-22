@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.ha.cards.DevicePacks
import ee.schimke.ha.cards.HaCardGarageDoor
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.rc.androidXExperimental

// The device packs (card-designs/ui-builder/packs, docs/design/device-packs.md): each UI Builder
// design drawn by the in-process `.uid` renderer (CardDesignDocument) and captured like any card,
// at its own design size, with its state variables' and components' seed values. A pack change
// shows in a pull request's preview diff. The renderer draws Remote Material 3 text and buttons in
// the HA theme, so these are close to UI Builder's own render, not identical to it.

/** [path]'s design (`garage-door/alert.uid`), captured and played as a card is. */
@Composable
private fun PackDesign(path: String) =
  CapturingRemoteContentPreview(profile = androidXExperimental) {
    checkNotNull(DevicePacks.design(path)) { "no pack design $path" }.Render()
  }

@Preview(name = "Garage door · Material 3", group = "Device packs", widthDp = 360, heightDp = 236)
@Composable
fun GarageDoorControlsMaterial3Preview() = PackDesign("garage-door/controls-material3.uid")

@Preview(name = "Garage door · Wall console", group = "Device packs", widthDp = 280, heightDp = 360)
@Composable
fun GarageDoorControlsSkeuomorphicPreview() = PackDesign("garage-door/controls-skeuomorphic.uid")

@Preview(name = "Garage door · Overlay", group = "Device packs", widthDp = 280, heightDp = 96)
@Composable
fun GarageDoorControlsOverlayPreview() = PackDesign("garage-door/controls-overlay.uid")

@Preview(name = "Garage door · Alert", group = "Device packs", widthDp = 360, heightDp = 232)
@Composable
fun GarageDoorAlertPreview() = PackDesign("garage-door/alert.uid")

@Preview(name = "Garage door · Toolbar item", group = "Device packs", widthDp = 132, heightDp = 40)
@Composable
fun GarageDoorToolbarPreview() = PackDesign("garage-door/toolbar.uid")

@Preview(name = "Garage door · Real world", group = "Device packs", widthDp = 360, heightDp = 300)
@Composable
fun GarageDoorRealWorldPreview() = PackDesign("garage-door/real-world.uid")

@Preview(name = "Thermostat · Material 3", group = "Device packs", widthDp = 360, heightDp = 340)
@Composable
fun ThermostatControlsMaterial3Preview() = PackDesign("thermostat/controls-material3.uid")

@Preview(name = "Thermostat · Wall unit", group = "Device packs", widthDp = 280, heightDp = 232)
@Composable
fun ThermostatControlsSkeuomorphicPreview() = PackDesign("thermostat/controls-skeuomorphic.uid")

@Preview(name = "Thermostat · Overlay", group = "Device packs", widthDp = 280, heightDp = 96)
@Composable
fun ThermostatControlsOverlayPreview() = PackDesign("thermostat/controls-overlay.uid")

@Preview(name = "Thermostat · Alert", group = "Device packs", widthDp = 360, heightDp = 320)
@Composable
fun ThermostatAlertPreview() = PackDesign("thermostat/alert.uid")

@Preview(name = "Thermostat · Toolbar item", group = "Device packs", widthDp = 148, heightDp = 52)
@Composable
fun ThermostatToolbarPreview() = PackDesign("thermostat/toolbar.uid")

@Preview(name = "Thermostat · Real world", group = "Device packs", widthDp = 360, heightDp = 300)
@Composable
fun ThermostatRealWorldPreview() = PackDesign("thermostat/real-world.uid")

/** The skeuomorphic garage door closed, opening at 40 % (the porch light on) and open. */
@Preview(name = "Garage door · Travel", group = "Device packs", widthDp = 528, heightDp = 140)
@Composable
fun GarageDoorTravelPreview() =
  CapturingRemoteContentPreview(profile = androidXExperimental) {
    RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(12.rdp)) {
      HaCardGarageDoor(
        position = 0f,
        stateInt = HaLiveBindings.COVER_CLOSED,
        widthDp = 168,
        heightDp = 140,
      )
      HaCardGarageDoor(
        position = 40f,
        stateInt = HaLiveBindings.COVER_OPENING,
        widthDp = 168,
        heightDp = 140,
      )
      HaCardGarageDoor(
        position = 100f,
        stateInt = HaLiveBindings.COVER_OPEN,
        widthDp = 168,
        heightDp = 140,
      )
    }
  }
