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
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

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
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        RemoteText(
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
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
        if (visualFamily() == HaVisualFamily.Console)
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
fun RemoteHaCalendarIdentity(data: HaCalendarData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val next = data.events.firstOrNull()
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
        text = data.title.rs,
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (next != null) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
          RemoteBox(
            modifier =
              RemoteModifier.size(8.rdp)
                .clip(haControlShape(4, round = true))
                .background(haInk(next.accent))
          )
          RemoteText(
            text = next.whenLabel.rs,
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            modifier = RemoteModifier.padding(start = 8.rdp),
          )
        }
        RemoteText(
          text = next.summary.rs,
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.SemiBold,
          style = haTextStyle(),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      } else {
        if (visualFamily() == HaVisualFamily.Console)
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
          .background(haInk(event.accent))
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
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
