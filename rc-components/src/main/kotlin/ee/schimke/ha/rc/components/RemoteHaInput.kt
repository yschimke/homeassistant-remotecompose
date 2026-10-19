@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.capture.LocalRemoteComposeCreationState
import androidx.compose.remote.creation.compose.layout.RemoteCustomComponent
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
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
    androidx.compose.remote.creation.compose.layout.RemoteText(
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
  androidx.compose.remote.creation.compose.layout.RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding((if (showFallback) 4 else 12).rdp)
  ) {
    androidx.compose.remote.creation.compose.layout.RemoteColumn {
      title
        ?.takeUnless { showFallback }
        ?.let {
          androidx.compose.remote.creation.compose.layout.RemoteText(
            it.rs,
            color = theme.primaryText,
            fontSize = HaType.Title,
          )
        }
      if (showFallback) {
        val open = HaAction.Navigate("/lovelace").toRemoteAction()
        androidx.compose.remote.creation.compose.layout.RemoteText(
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
  RcOpcodes.LAYOUT_CUSTOM in LocalRemoteComposeCreationState.current.profile.supportedOperations
