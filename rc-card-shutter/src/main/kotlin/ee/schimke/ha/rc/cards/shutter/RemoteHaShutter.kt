@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards.shutter

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaType
import ee.schimke.ha.rc.components.cardChrome
import ee.schimke.ha.rc.components.currentRemoteHaTheme

/**
 * Minimal port of `enhanced-shutter-card` (HACS) — native Compose, no image compositing, no tilt,
 * no 3D, no presets. One shutter row per entity: name, a window-plus-shutter visualisation, up /
 * stop / down buttons, and a position label.
 *
 * Position, button states and the label are captured at emission time (from the cover's state and
 * `current_position`); the converter's `dataSignature` makes the host re-encode the card whenever
 * the cover changes (path 2 of `docs/architecture/live-card-updates.md`).
 */
data class HaShutterCardData(val title: RemoteString?, val entries: List<HaShutterEntryData>)

/** One shutter inside the card. */
data class HaShutterEntryData(
  val name: RemoteString,
  /** Fraction of the window covered, 0f (fully open) … 1f (fully closed). */
  val closedFraction: Float,
  val stateLabel: RemoteString,
  val showNameOnTop: Boolean,
  val openAction: HaAction,
  val closeAction: HaAction,
  val stopAction: HaAction,
  /** Which of the up / stop / down buttons are shown and tappable ([coverControls]). */
  val controls: CoverControls = CoverControls.AllEnabled,
  /** Direction of travel, drawn as an arrow on the shutter or door. */
  val motion: GarageMotion = GarageMotion.Idle,
  /** Draw a garage door (`device_class: garage` / `gate`) instead of a window shutter. */
  val garage: Boolean = false,
)

@Composable
@RemoteComposable
fun RemoteHaShutter(data: HaShutterCardData, modifier: RemoteModifier = RemoteModifier) {
  val theme = currentRemoteHaTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 10.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(10.rdp)) {
      if (data.title != null) {
        RemoteText(
          text = data.title,
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      data.entries.forEach { entry -> ShutterEntry(entry) }
    }
  }
}

@Composable
@RemoteComposable
private fun ShutterEntry(entry: HaShutterEntryData) {
  val theme = currentRemoteHaTheme()
  RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(4.rdp)) {
    if (entry.showNameOnTop) {
      RemoteText(
        text = entry.name,
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteRow(
      verticalAlignment = RemoteAlignment.CenterVertically,
      horizontalArrangement = RemoteArrangement.spacedBy(12.rdp),
    ) {
      if (entry.garage) GarageDoorDrawing(entry.closedFraction, entry.motion)
      else ShutterWindowDrawing(entry.closedFraction, entry.motion)
      CoverButtonColumn(entry.controls, entry.openAction, entry.stopAction, entry.closeAction)
    }
    RemoteText(
      text = entry.stateLabel,
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = RemoteTextStyle.Default,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
