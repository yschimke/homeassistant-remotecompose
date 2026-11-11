@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable

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
        .padding(horizontal = 14.rdp, vertical = 12.rdp),
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
      if (data.title != null) {
        RemoteText(
          text = cardTitle(data.title),
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = RemoteFontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = RemoteTextOverflow.Ellipsis,
        )
      }
      if (data.entries.isEmpty()) {
        if (haLayouts().notes == HaNoteStyle.Tape)
          ConsoleTapeLabel("No recent activity".rs, compact = false)
        else
          RemoteText(
            text = "No recent activity".rs,
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
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
        text = latest.liveName(),
        color = theme.primaryText,
        fontSize = HaType.Caption,
        fontWeight = RemoteFontWeight.SemiBold,
        style = haTextStyle(),
        maxLines = 2,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      RemoteText(
        text = latest.liveMessage(),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
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
    if (latest == null) {
      if (haLayouts().notes == HaNoteStyle.Tape)
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
          contentDescription = latest.liveName(),
          modifier = RemoteModifier.size(22.rdp),
          tint = theme.secondaryText,
        )
        RemoteColumn(
          modifier = RemoteModifier.weight(1f.rf).padding(start = 12.rdp),
          verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) {
          // The latest entry is row 0: the same keys as the full list, so a template bakes it.
          RemoteText(
            text = latest.liveName(),
            color = theme.primaryText,
            fontSize = HaType.Title,
            fontWeight = RemoteFontWeight.SemiBold,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
          RemoteText(
            text = latest.liveMessage(),
            color = theme.secondaryText,
            fontSize = HaType.Label,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/**
 * The row's name, `<id>.name`: in a template the slot's name, otherwise the entity's own
 * (`HaLiveBindings.name`), which every host pushes, so a rename needs no new document.
 */
@Composable
private fun HaLogbookEntry.liveName(): RemoteString =
  if (LocalTemplateBindings.current != null) entityName(entityId, name)
  else LiveValues.named(entityId, "name", name)

/** What the row's entity is now: `<id>.state`, the host's own formatted state. */
private fun HaLogbookEntry.liveMessage(): RemoteString = LiveValues.state(entityId, message)

/** When it changed, relative to the player's clock; a row without an entity draws [whenText]. */
private fun HaLogbookEntry.liveWhen(): RemoteString =
  if (entityId == null) whenText.rs
  else
    relativeTimeText(
      LiveValues.lastChanged(entityId, lastChanged),
      LiveValues.lastChangedOffset(entityId, lastChangedOffset),
    )

/**
 * The Wear Small summary line: the latest entry's name and what it is now, or that there is none.
 * Built from the row's own bindings, so it follows the entity like the full card.
 */
@Composable
fun logbookSummaryText(data: HaLogbookData): RemoteString {
  val latest = data.entries.firstOrNull() ?: return "No recent activity".rs
  return latest.liveName() + " " + latest.liveMessage()
}

@Composable
private fun Entry(entry: HaLogbookEntry, theme: RemoteHaTheme) {
  val name = entry.liveName()
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
    RemoteColumn(modifier = RemoteModifier.weight(1f.rf).padding(start = 10.rdp)) {
      RemoteText(
        text = name,
        color = theme.primaryText,
        fontSize = HaType.Body,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      RemoteText(
        text = entry.liveMessage(),
        color = theme.secondaryText,
        fontSize = HaType.Label,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
    }
    RemoteText(
      text = entry.liveWhen(),
      color = theme.secondaryText,
      fontSize = HaType.Caption,
      style = haTextStyle(),
      maxLines = 1,
    )
  }
}
