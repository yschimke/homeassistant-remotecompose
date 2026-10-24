@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

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
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
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
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}
