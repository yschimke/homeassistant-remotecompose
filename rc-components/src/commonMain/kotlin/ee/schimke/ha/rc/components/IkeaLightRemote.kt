@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import androidx.compose.runtime.Composable

/**
 * STYRBAR-inspired cruciform remote. Four honest targets: explicit power and brightness steps. The
 * moulded body owns no click, so a brightness press cannot also toggle the light. Off-state dimming
 * is inert, exactly like the converter's existing steppers. No colour-cycle key is drawn because
 * the shared light contract has no colour-temperature action.
 */
@Composable
@RemoteComposable
internal fun IkeaLightRemote(data: HaArcDialData, modifier: RemoteModifier) {
  val theme = haTheme()
  val accent = haInk(data.accent)
  fun power(service: String): HaAction =
    data.entityId?.let { HaAction.CallService("light", service, it) } ?: HaAction.None
  RemoteColumn(
    modifier
      .fillMaxWidth()
      .then(cardChrome(theme.cardBackground, theme.divider))
      .padding(horizontal = 14.rdp, vertical = 12.rdp),
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
    verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
  ) {
    RemoteText(
      entityName(data.entityId, data.name),
      color = theme.primaryText,
      fontSize = HaType.BodyLarge,
      style = haTextStyle(),
      fontWeight = RemoteFontWeight.Bold,
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
    )
    RemoteText(
      LiveValues.attribute(data.entityId, data.centerLabelAttribute, data.centerLabel),
      color = theme.primaryText,
      fontSize = HaType.Value,
      style = haTextStyle(),
      fontWeight = haValueWeight(),
      maxLines = 1,
    )
    RemoteColumn(
      RemoteModifier.size(148.rdp).then(ikeaKeyChrome(accent)),
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
      verticalArrangement = RemoteArrangement.Center,
    ) {
      IkeaRemoteKey(
        "On",
        power("turn_on"),
        theme.primaryText,
        if (data.showSteppers) accent else null,
      )
      RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(48.rdp)) {
        IkeaRemoteKey(
          "−",
          data.decrementAction,
          if (data.showSteppers) theme.primaryText else theme.secondaryText,
        )
        IkeaRemoteKey(
          "+",
          data.incrementAction,
          if (data.showSteppers) theme.primaryText else theme.secondaryText,
        )
      }
      IkeaRemoteKey(
        "Off",
        power("turn_off"),
        theme.primaryText,
        if (!data.showSteppers) theme.linkText else null,
      )
    }
    RemoteText(
      "− / +  Brightness".rs,
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
    )
  }
}

@Composable
private fun IkeaRemoteKey(
  label: String,
  action: HaAction,
  color: RemoteColor,
  selectedAccent: RemoteColor? = null,
) {
  val click = action.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteBox(
    RemoteModifier.size(48.rdp).then(click).then(ikeaKeyChrome(selectedAccent)),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteText(
      label.rs,
      color = color,
      fontSize = HaType.BodyLarge,
      style = haTextStyle(),
      fontWeight = RemoteFontWeight.Bold,
      maxLines = 1,
    )
  }
}
