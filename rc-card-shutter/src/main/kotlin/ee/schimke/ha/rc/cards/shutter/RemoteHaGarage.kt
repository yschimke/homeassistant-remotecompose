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
 * `custom:garage-shutter-card` card data. One row per garage door: name, sectional-door
 * visualisation, up / stop / down buttons, state label. The visualisation renders a horizontal 5:3
 * frame (vs the window shutter's near-square 6:5 frame) with horizontal panel divisions on the door
 * so it reads as garage rather than blinds.
 *
 * Like [HaShutterCardData] the [HaGarageEntryData.closedFraction] is captured statically at
 * emission time; the converter's `dataSignature` re-encodes the card when the cover changes. The
 * previews in `:previews/GarageShutterCardPreviews.kt` exercise the 0% / 25% / 50% / 75% / 100%
 * storyboard for both themes so each frame of the open/close cycle is visually verifiable.
 */
data class HaGarageCardData(val title: RemoteString?, val entries: List<HaGarageEntryData>)

/**
 * One garage door inside the card.
 *
 * @property closedFraction 0f = fully open (door tucked into ceiling track), 1f = fully closed
 *   (door at floor). Matches the shutter card's convention so the two cards' fractions can share
 *   host code.
 * @property motion drives the in-frame motion hint — a chevron pointing up while opening, down
 *   while closing, hidden when idle. Indicates direction independent of the position label, which
 *   is useful for transitional states where `current_position` hasn't updated yet.
 * @property controls which of the up / stop / down buttons are shown and tappable
 *   ([coverControls]).
 */
data class HaGarageEntryData(
  val name: RemoteString,
  val closedFraction: Float,
  val motion: GarageMotion,
  val stateLabel: RemoteString,
  val showNameOnTop: Boolean,
  val openAction: HaAction,
  val closeAction: HaAction,
  val stopAction: HaAction,
  val controls: CoverControls = CoverControls.AllEnabled,
)

/** Direction of in-progress motion, drives the motion-arrow overlay. */
enum class GarageMotion {
  Idle,
  Opening,
  Closing,
}

@Composable
@RemoteComposable
fun RemoteHaGarage(data: HaGarageCardData, modifier: RemoteModifier = RemoteModifier) {
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
      data.entries.forEach { entry -> GarageEntry(entry) }
    }
  }
}

@Composable
@RemoteComposable
private fun GarageEntry(entry: HaGarageEntryData) {
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
      GarageDoorDrawing(entry.closedFraction, entry.motion)
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
