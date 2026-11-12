@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon

/**
 * Visible placeholder for card types we haven't implemented yet. Keeps the dashboard renderable
 * instead of silently dropping unknown cards.
 *
 * [compact] sets the icon beside the two lines rather than above them, for a short fixed cell (a
 * Wear Small slot is 60 dp tall, too short for the stacked form).
 */
@Composable
@RemoteComposable
fun RemoteHaUnsupported(
  data: HaUnsupportedData,
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .then(cardChrome(theme.placeholderBackground, theme.placeholderAccent))
        .padding(12.rdp),
    contentAlignment = RemoteAlignment.CenterStart,
  ) {
    if (haLayouts().notes == HaNoteStyle.Tape) {
      ConsoleTapeLabel("Not yet supported".rs, detail = data.cardType.rs)
    } else if (compact)
      RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
        UnsupportedIcon()
        RemoteColumn(modifier = RemoteModifier.padding(start = 12.rdp)) { UnsupportedLines(data) }
      }
    else
      RemoteColumn {
        UnsupportedIcon()
        RemoteBox(modifier = RemoteModifier.padding(top = 8.rdp)) { UnsupportedTitle() }
        UnsupportedDetail(data)
      }
  }
}

@Composable
private fun UnsupportedIcon() {
  RemoteBasicIcon(
    imageVector = Icons.Filled.Widgets.toRemoteImageVector(),
    contentDescription = "Unsupported card".rs,
    modifier = RemoteModifier.size(24.rdp),
    tint = haTheme().placeholderAccent,
  )
}

@Composable
private fun UnsupportedLines(data: HaUnsupportedData) {
  UnsupportedTitle()
  UnsupportedDetail(data)
}

@Composable
private fun UnsupportedTitle() {
  RemoteText(
    text = "Not yet supported".rs,
    color = haTheme().primaryText,
    fontSize = HaType.BodyLarge,
    fontWeight = RemoteFontWeight.Medium,
    style = haTextStyle(),
  )
}

@Composable
private fun UnsupportedDetail(data: HaUnsupportedData) {
  RemoteText(
    text = data.cardType.rs,
    color = haTheme().secondaryText,
    fontSize = HaType.Label,
    style = haTextStyle(),
  )
}
