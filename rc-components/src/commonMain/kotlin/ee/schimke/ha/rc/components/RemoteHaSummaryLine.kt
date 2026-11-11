@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rdp
import androidx.compose.runtime.Composable

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
        overflow = RemoteTextOverflow.Ellipsis,
      )
      RemoteText(
        text = line,
        color = theme.primaryText,
        fontSize = HaType.BodyLarge,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
    }
  }
}
