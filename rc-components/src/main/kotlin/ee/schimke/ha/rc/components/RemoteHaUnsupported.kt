@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

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
    if (visualFamily() == HaVisualFamily.Console) {
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
  RemoteIcon(
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
    fontWeight = FontWeight.Medium,
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
