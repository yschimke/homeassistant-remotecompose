@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
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
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

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
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (data.entries.isEmpty()) {
        if (visualFamily() == HaVisualFamily.Console)
          ConsoleTapeLabel("No recent activity".rs, compact = false)
        else
          RemoteText(
            text = "No recent activity".rs,
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
          )
      } else {
        data.entries.forEachIndexed { i, entry -> Entry(i, entry, theme) }
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
fun RemoteHaLogbookIdentity(
  data: HaLogbookData,
  modifier: RemoteModifier = RemoteModifier,
  narrow: Boolean = false,
) {
  val theme = haTheme()
  val latest = data.entries.firstOrNull()
  // One launcher column (`57 dp`) leaves no room beside the icon: the icon over the latest entry's
  // name and message.
  if (narrow && latest != null) {
    RemoteColumn(
      modifier =
        modifier
          .fillMaxSize()
          .then(cardChrome(theme.cardBackground, theme.divider))
          .padding(horizontal = 6.rdp, vertical = 8.rdp),
      horizontalAlignment = RemoteAlignment.CenterHorizontally,
      verticalArrangement = RemoteArrangement.spacedBy(2.rdp, RemoteAlignment.CenterVertically),
    ) {
      RemoteBasicIcon(
        imageVector = latest.icon.toRemoteImageVector(),
        contentDescription = null,
        modifier = RemoteModifier.size(20.rdp),
        tint = theme.secondaryText,
      )
      RemoteText(
        text = cardText("row.0.name", latest.name),
        color = theme.primaryText,
        fontSize = HaType.Caption,
        fontWeight = FontWeight.SemiBold,
        style = haTextStyle(),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = cardText("row.0.message", latest.message),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
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
    if (latest == null) {
      if (visualFamily() == HaVisualFamily.Console)
        ConsoleTapeLabel("No recent activity".rs, compact = true)
      else
        RemoteText(
          text = "No recent activity".rs,
          color = theme.secondaryText,
          fontSize = HaType.Body,
          style = haTextStyle(),
        )
    } else {
      RemoteRow(
        modifier = RemoteModifier.fillMaxWidth(),
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        RemoteBasicIcon(
          imageVector = latest.icon.toRemoteImageVector(),
          contentDescription = latest.name.rs,
          modifier = RemoteModifier.size(22.rdp),
          tint = theme.secondaryText,
        )
        RemoteColumn(
          modifier = RemoteModifier.weight(1f).padding(start = 12.rdp),
          verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) {
          // The latest entry is row 0: the same keys as the full list, so a template bakes it.
          RemoteText(
            text = cardText("row.0.name", latest.name),
            color = theme.primaryText,
            fontSize = HaType.Title,
            fontWeight = FontWeight.SemiBold,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          RemoteText(
            text = cardText("row.0.message", latest.message),
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun Entry(index: Int, entry: HaLogbookEntry, theme: RemoteHaTheme) {
  val name = cardText("row.$index.name", entry.name)
  RemoteRow(
    modifier = RemoteModifier.fillMaxWidth(),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteBasicIcon(
      imageVector = entry.icon.toRemoteImageVector(),
      // Decorative: the name is drawn beside it.
      contentDescription = null,
      modifier = RemoteModifier.size(16.rdp),
      tint = theme.secondaryText,
    )
    RemoteColumn(modifier = RemoteModifier.weight(1f).padding(start = 10.rdp)) {
      RemoteText(
        text = name,
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = cardText("row.$index.message", entry.message),
        color = theme.secondaryText,
        fontSize = HaType.Label,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RemoteText(
      text = cardText("row.$index.when", entry.whenText),
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
    )
  }
}
