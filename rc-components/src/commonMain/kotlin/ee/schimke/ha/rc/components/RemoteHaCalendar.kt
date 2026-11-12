@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
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
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow

/**
 * `calendar` card — title + chronological list of upcoming events. Rendered as a list rather than a
 * month-grid because the list survives watch/phone-widget squeezes and conveys the same data in
 * less space.
 */
@Composable
@RemoteComposable
fun RemoteHaCalendar(data: HaCalendarData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        // The title takes what the range leaves, with its own gap: the range never runs into it.
        RemoteText(
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = RemoteFontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
          modifier = RemoteModifier.weight(1f.rf).padding(end = 8.rdp),
        )
        RemoteText(
          text = cardText("range", data.rangeLabel),
          color = theme.secondaryText,
          fontSize = HaType.Caption,
          style = haTextStyle(),
          maxLines = 1,
        )
      }
      if (data.events.isEmpty()) {
        if (haLayouts().notes == HaNoteStyle.Tape)
          ConsoleTapeLabel("No upcoming events".rs, compact = false)
        else
          RemoteText(
            text = "No upcoming events".rs,
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
          )
      } else {
        data.events.forEachIndexed { i, event -> Event(i, event, theme) }
      }
    }
  }
}

/**
 * Identity tier for the `calendar` family — the next event only: its accent dot, when-label and
 * summary, with the calendar title above. The smallest cell that still answers "what's next"; drops
 * the rest of the agenda (P5) but keeps the P1 identity (next event). Used by the Fixed-mode
 * converter at narrow launcher / Wear cells; see docs/architecture/adaptive-card-layouts.md §"Bulk
 * / time-series".
 */
@Composable
@RemoteComposable
fun RemoteHaCalendarIdentity(
  data: HaCalendarData,
  modifier: RemoteModifier = RemoteModifier,
  narrow: Boolean = false,
) {
  val theme = haTheme()
  val next = data.events.firstOrNull()
  // One launcher column (`57 dp`) holds neither the title nor a date beside the dot: the next
  // event's name alone, on up to three lines.
  if (narrow && next != null) {
    RemoteBox(
      modifier =
        modifier
          .fillMaxSize()
          .then(cardChrome(theme.cardBackground, theme.divider))
          .padding(horizontal = 6.rdp, vertical = 8.rdp),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteText(
        text = cardText("row.0.summary", next.summary),
        color = theme.primaryText,
        fontSize = HaType.Label,
        fontWeight = RemoteFontWeight.SemiBold,
        style = haTextStyle(),
        maxLines = 3,
        overflow = RemoteTextOverflow.Ellipsis,
      )
    }
    return
  }
  RemoteBox(
    modifier =
      modifier
        .fillMaxSize()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteColumn(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(3.rdp),
    ) {
      RemoteText(
        text = cardTitle(data.title),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      if (next != null) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
          RemoteBox(
            modifier =
              RemoteModifier.size(8.rdp)
                .clip(haControlShape(4, round = true))
                .background(haInk(next.accent)),
          )
          RemoteText(
            // The next event is row 0: the full agenda's keys, so a template bakes it.
            text = cardText("row.0.when", next.whenLabel),
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            modifier = RemoteModifier.padding(start = 8.rdp),
          )
        }
        RemoteText(
          text = cardText("row.0.summary", next.summary),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = RemoteFontWeight.SemiBold,
          style = haTextStyle(),
          maxLines = 2,
          overflow = RemoteTextOverflow.Ellipsis,
        )
      } else {
        if (haLayouts().notes == HaNoteStyle.Tape)
          ConsoleTapeLabel("No upcoming events".rs, compact = true)
        else
          RemoteText(
            text = "No upcoming events".rs,
            color = theme.secondaryText,
            fontSize = HaType.Body,
            style = haTextStyle(),
          )
      }
    }
  }
}

@Composable
private fun Event(index: Int, event: HaCalendarEvent, theme: RemoteHaTheme) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth().padding(vertical = 2.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(8.rdp)
          .clip(haControlShape(4, round = true))
          .background(haInk(event.accent)),
    )
    RemoteColumn(modifier = RemoteModifier.padding(start = 10.rdp)) {
      RemoteText(
        text = cardText("row.$index.when", event.whenLabel),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
      )
      RemoteText(
        text = cardText("row.$index.summary", event.summary),
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
    }
  }
}
