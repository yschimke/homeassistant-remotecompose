@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * `custom:ha-bambulab-print_control-card` — the pad of the printer's pause / resume / stop button
 * entities that apply now: Pause while printing, Resume while paused, Stop in either. A control
 * whose entity is unavailable is greyed out and inert. With no control to show, the pad states why.
 */
@Composable
@RemoteComposable
fun RemoteHaBambuPrintControl(
  data: HaBambuPrintControlData,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteText(
        text = cardText("printer", data.printerName),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
      )
      val buttons = listOfNotNull(data.pause, data.resume, data.stop)
      if (buttons.isEmpty()) {
        RemoteText(
          text = cardText("status", data.statusLine ?: ""),
          color = theme.primaryText,
          fontSize = HaType.Body,
          style = haTextStyle(),
        )
      } else {
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          horizontalArrangement = RemoteArrangement.SpaceEvenly,
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          buttons.forEach { btn -> Button(btn, theme) }
        }
      }
    }
  }
}

@Composable
private fun Button(button: HaBambuControlButton, theme: RemoteHaTheme) {
  // An unavailable control is inert: no click, and drawn in the theme's quiet text colour.
  val click =
    button.tapAction
      .takeIf { button.enabled }
      ?.toRemoteAction()
      ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteColumn(
    modifier = RemoteModifier.then(click),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    val accent = if (button.enabled) haInk(button.accent) else theme.secondaryText
    RemoteBox(
      modifier =
        RemoteModifier.size(48.rdp)
          .clip(haControlShape(24, round = true))
          .background(accent.copy(alpha = accent.alpha * 0.18f.rf)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteIcon(
        imageVector = button.icon.toRemoteImageVector(),
        contentDescription = button.label.rs,
        modifier = RemoteModifier.size(24.rdp),
        tint = accent,
      )
    }
    RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
      RemoteText(
        text = button.label.rs,
        color = if (button.enabled) theme.primaryText else theme.secondaryText,
        fontSize = HaType.Label,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
      )
    }
  }
}
