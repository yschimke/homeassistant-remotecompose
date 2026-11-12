@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon

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
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
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
        if (haLayouts().notes == HaNoteStyle.Tape)
          ConsoleTapeLabel(cardText("status", data.statusLine ?: "No controls available"))
        else
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
      ?.let { RemoteModifier.haClickable(it) } ?: RemoteModifier
  RemoteColumn(
    modifier = RemoteModifier.then(click),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    val accent = if (button.enabled) haInk(button.accent) else theme.secondaryText
    val console = haLayouts().keyFaces
    RemoteBox(
      modifier =
        RemoteModifier.size(48.rdp)
          .clip(haControlShape(24, round = true))
          .then(
            if (console) RemoteModifier
            else RemoteModifier.background(accent.copy(alpha = accent.alpha * 0.18f.rf)),
          ),
      contentAlignment = RemoteAlignment.Center,
    ) {
      if (console)
        ConsoleControlFace(
          if (button.enabled) accent else ConsoleSlot.rc,
          RemoteModifier.size(48.rdp),
        )
      RemoteBasicIcon(
        imageVector = button.icon.toRemoteImageVector(),
        contentDescription = button.label.rs,
        modifier =
          RemoteModifier.then(if (console) RemoteModifier.padding(end = 13.rdp) else RemoteModifier)
            .size(24.rdp),
        tint = if (console && button.enabled) theme.primaryText else accent,
      )
    }
    RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
      RemoteText(
        text = button.label.rs,
        color = if (button.enabled) theme.primaryText else theme.secondaryText,
        fontSize = HaType.Label,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
      )
    }
  }
}
