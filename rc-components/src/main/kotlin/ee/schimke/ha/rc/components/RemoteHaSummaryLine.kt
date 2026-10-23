@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * The smallest tier of a card whose full layout is a list or a page (calendar, to-do, logbook): its
 * [title] in caption type over one [line] of what it holds, the next event, "3 left", the latest
 * entry. Two short lines fit a Wear Small slot, about 44 dp of content, where a title over a list
 * loses its second line.
 */
@Composable
@RemoteComposable
fun RemoteHaSummaryLine(
  title: RemoteString,
  line: RemoteString,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  RemoteBox(
    modifier = modifier.fillMaxSize().padding(horizontal = 14.rdp, vertical = 4.rdp),
    contentAlignment = RemoteAlignment.CenterStart,
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(HaSpace.XXS.rdp),
    ) {
      RemoteText(
        text = title,
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = line,
        color = theme.primaryText,
        fontSize = HaType.BodyLarge,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
