@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.capture.LocalRemoteComposeCreationState
import ee.schimke.composeai.rccreation.compose.layout.RemoteCustomComponent
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import androidx.compose.runtime.Composable
import ee.schimke.composeai.rcplayer.protocol.RcInput
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes

/** The same read id and TEXT_RETURN target; editing goes through the document's text table. */
@Composable
fun RemoteHaInput(
  key: String,
  label: String,
  value: String,
  options: List<String> = emptyList(),
  multiline: Boolean = false,
  submit: String = "",
  chrome: String = "default",
) {
  if (!supportsInputComponents()) {
    ee.schimke.composeai.rccreation.compose.layout.RemoteText(
      "$label · Open app to edit".rs,
      color = haTheme().primaryText,
      fontSize = HaType.Body,
    )
    return
  }
  val text = value.rs
  RemoteCustomComponent(
    name = if (options.isEmpty()) RcInput.TEXT else RcInput.CHOICE,
    modifier =
      RemoteModifier.fillMaxWidth()
        .height((if (multiline) 136 else if (submit.isNotEmpty()) 112 else 80).rdp),
    properties = {
      property(RcInput.VALUE, text)
      bindReturn(RcInput.VALUE_RETURN, text)
      property(RcInput.KEY, key)
      property(RcInput.LABEL, label)
      property(RcInput.MULTILINE, if (multiline) 1 else 0)
      property(RcInput.OPTIONS, options.joinToString("\n"))
      property(RcInput.SUBMIT, submit)
      property(RcInput.CHROME, if (chrome == "none") 1 else 0)
    },
  )
}

/** Shared themed form chrome; fixed widget captures emit a useful open-app alternative. */
@Composable
fun RemoteHaInputForm(
  title: String?,
  fallback: Boolean,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable () -> Unit,
) {
  val theme = haTheme()
  val showFallback = fallback || !supportsInputComponents()
  ee.schimke.composeai.rccreation.compose.layout.RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding((if (showFallback) 4 else 12).rdp),
  ) {
    ee.schimke.composeai.rccreation.compose.layout.RemoteColumn {
      title
        ?.takeUnless { showFallback }
        ?.let {
          ee.schimke.composeai.rccreation.compose.layout.RemoteText(
            it.rs,
            color = theme.primaryText,
            fontSize = HaType.Title,
          )
        }
      if (showFallback) {
        val open = HaAction.Navigate("/lovelace").toRemoteAction()
        ee.schimke.composeai.rccreation.compose.layout.RemoteText(
          "Open app to send a message".rs,
          color = theme.primaryText,
          fontSize = HaType.Body,
          modifier = if (open != null) RemoteModifier.clickable(open) else RemoteModifier,
        )
      } else content()
    }
  }
}

@Composable
private fun supportsInputComponents(): Boolean =
  LocalRemoteComposeCreationState.current.supportsOperation(RcOpcodes.LAYOUT_CUSTOM)
