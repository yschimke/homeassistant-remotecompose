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
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `logbook` card — list of recent state-change entries.
 *
 * ```
 *   ┌──────────────────────────────────────────────┐
 *   │  Title                                       │
 *   │  ●  Front door  unlocked        2 min ago    │
 *   │  ●  Kitchen     turned on       4 min ago    │
 *   │  ●  Garage      opened          8 min ago    │
 *   └──────────────────────────────────────────────┘
 * ```
 */
@Composable
@RemoteComposable
fun RemoteHaLogbook(data: HaLogbookData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp)
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      if (data.title != null) {
        RemoteText(
          text = data.title.rs,
          color = theme.primaryText.rc,
          fontSize = 15.rsp,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (data.entries.isEmpty()) {
        RemoteText(
          text = "No recent activity".rs,
          color = theme.secondaryText.rc,
          fontSize = 12.rsp,
          style = RemoteTextStyle.Default,
        )
      } else {
        data.entries.forEach { entry -> Entry(entry, theme) }
      }
    }
  }
}

/**
 * Identity tier for the `logbook` family — the latest entry only: its icon, name, what changed and
 * when. The smallest cell that still says "here's the most recent activity"; drops the older
 * entries (P5) but keeps the P1 identity (latest entry). Used by the Fixed-mode converter at narrow
 * launcher / Wear cells; see docs/architecture/adaptive-card-layouts.md §"Bulk / time-series".
 */
@Composable
@RemoteComposable
fun RemoteHaLogbookIdentity(data: HaLogbookData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val latest = data.entries.firstOrNull()
  RemoteBox(
    modifier =
      modifier
        .fillMaxSize()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    if (latest == null) {
      RemoteText(
        text = "No recent activity".rs,
        color = theme.secondaryText.rc,
        fontSize = 13.rsp,
        style = RemoteTextStyle.Default,
      )
    } else {
      RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        RemoteIcon(
          imageVector = latest.icon,
          contentDescription = latest.name.rs,
          modifier = RemoteModifier.size(22.rdp),
          tint = theme.secondaryText.rc,
        )
        RemoteColumn(
          modifier = RemoteModifier.weight(1f).padding(start = 12.rdp),
          verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) {
          RemoteText(
            text = latest.name.rs,
            color = theme.primaryText.rc,
            fontSize = 15.rsp,
            fontWeight = FontWeight.SemiBold,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          RemoteText(
            text = latest.message.rs,
            color = theme.secondaryText.rc,
            fontSize = 12.rsp,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun Entry(entry: HaLogbookEntry, theme: HaTheme) {
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteIcon(
      imageVector = entry.icon,
      contentDescription = entry.name.rs,
      modifier = RemoteModifier.size(16.rdp),
      tint = theme.secondaryText.rc,
    )
    RemoteColumn(modifier = RemoteModifier.weight(1f).padding(start = 10.rdp)) {
      RemoteText(
        text = entry.name.rs,
        color = theme.primaryText.rc,
        fontSize = 13.rsp,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = entry.message.rs,
        color = theme.secondaryText.rc,
        fontSize = 12.rsp,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteText(
      text = entry.whenText.rs,
      color = theme.secondaryText.rc,
      fontSize = 11.rsp,
      style = RemoteTextStyle.Default,
      maxLines = 1,
    )
  }
}
