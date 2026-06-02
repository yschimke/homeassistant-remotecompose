@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * `media-control` card — title/artist + transport buttons + a position bar. Album art renders as a
 * tinted music-note placeholder; pulling arbitrary HTTP image entities into the .rc document needs
 * a media channel alpha08 doesn't expose.
 */
@Composable
@RemoteComposable
fun RemoteHaMediaControl(
  data: HaMediaControlData,
  modifier: RemoteModifier = RemoteModifier,
  fillHeight: Boolean = false,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .then(if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth())
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 12.rdp)
  ) {
    // Box→Column→Row, never Box→Row: a fillMaxSize Row directly under a
    // wrap-content Box (this card's root, or the breakpoint's
    // immediateSwap wrapper in Fixed mode) captures blank in alpha010;
    // the Column buffer fixes it (see § Known gaps #9).
    RemoteColumn(
      modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth()
    ) {
      RemoteRow(
        modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth(),
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        // Body. In fillHeight mode (a cell taller than the controls
        // need) weighted spacers push the title to the top, the
        // transport to the middle and the seek bar to the bottom so
        // the controls span the cell instead of clustering at the
        // top over a blank half (Principle 7/8). Otherwise the column
        // wraps its content as the HA reference does.
        RemoteColumn(
          modifier =
            if (fillHeight) RemoteModifier.weight(1f).fillMaxHeight().padding(end = 12.rdp)
            else RemoteModifier.weight(1f).padding(end = 12.rdp)
        ) {
          TitleBlock(data, theme)
          if (fillHeight) RemoteBox(modifier = RemoteModifier.weight(1f))
          TransportRow(data)
          if (fillHeight) RemoteBox(modifier = RemoteModifier.weight(1f))
          SeekBlock(data, theme)
        }
        // Album-art placeholder, vertically centred beside the body.
        RemoteBox(
          modifier =
            RemoteModifier.size(96.rdp)
              .clip(RemoteRoundedCornerShape(8.rdp))
              .background(data.accent.rc.copy(alpha = data.accent.rc.alpha * 0.15f.rf)),
          contentAlignment = RemoteAlignment.Center,
        ) {
          RemoteIcon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = data.title.rs,
            modifier = RemoteModifier.size(40.rdp),
            tint = data.accent.rc,
          )
        }
      }
    }
  }
}

@Composable
private fun TitleBlock(data: HaMediaControlData, theme: HaTheme) {
  RemoteText(
    text = data.playerName.rs,
    color = data.accent.rc,
    fontSize = 11.rsp,
    fontWeight = FontWeight.Medium,
    style = RemoteTextStyle.Default,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
  RemoteText(
    text = LiveValues.attribute(data.entityId, "media_title", data.title),
    color = theme.primaryText.rc,
    fontSize = 16.rsp,
    fontWeight = FontWeight.Medium,
    style = RemoteTextStyle.Default,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
  if (data.artist != null) {
    RemoteText(
      text = LiveValues.attribute(data.entityId, "media_artist", data.artist),
      color = theme.secondaryText.rc,
      fontSize = 12.rsp,
      style = RemoteTextStyle.Default,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun TransportRow(data: HaMediaControlData) {
  RemoteRow(
    modifier = RemoteModifier.padding(top = 6.rdp),
    horizontalArrangement = RemoteArrangement.spacedBy(4.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    TransportButton(Icons.Filled.SkipPrevious, data.previousAction, data.accent)
    TransportButton(
      if (data.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
      data.playPauseAction,
      data.accent,
      big = true,
    )
    TransportButton(Icons.Filled.SkipNext, data.nextAction, data.accent)
  }
}

@Composable
private fun SeekBlock(data: HaMediaControlData, theme: HaTheme) {
  ProgressBar(data.positionFraction, data.accent, theme)
  if (data.positionLabel != null && data.durationLabel != null) {
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth(),
      horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
      RemoteText(
        text = LiveValues.attribute(data.entityId, "media_position_label", data.positionLabel),
        color = theme.secondaryText.rc,
        fontSize = 11.rsp,
        style = RemoteTextStyle.Default,
      )
      RemoteText(
        text = LiveValues.attribute(data.entityId, "media_duration_label", data.durationLabel),
        color = theme.secondaryText.rc,
        fontSize = 11.rsp,
        style = RemoteTextStyle.Default,
      )
    }
  }
}

/**
 * Wide-thin Fixed-mode media variant — album-art swatch on the left, title (+ player name) in the
 * middle, a single play/pause button on the right. Targets short/narrow widget cells (Wear S/L, the
 * `3×1` launcher chip) where the full card's transport row + seek bar won't fit. Keeps the card's
 * identity (art + play/pause, P1) and the track title (P3); drops the prev/next transport, seek bar
 * and time labels (P4/P5). The full card with the seek bar returns once the cell is wide enough.
 */
@Composable
@RemoteComposable
fun RemoteHaMediaControlWide(data: HaMediaControlData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  RemoteRow(
    modifier =
      modifier
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 10.rdp, vertical = 8.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
    horizontalArrangement = RemoteArrangement.spacedBy(10.rdp),
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(40.rdp)
          .clip(RemoteRoundedCornerShape(6.rdp))
          .background(data.accent.rc.copy(alpha = data.accent.rc.alpha * 0.15f.rf)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteIcon(
        imageVector = Icons.Filled.MusicNote,
        contentDescription = data.title.rs,
        modifier = RemoteModifier.size(22.rdp),
        tint = data.accent.rc,
      )
    }
    RemoteColumn(
      modifier = RemoteModifier.weight(1f),
      verticalArrangement = RemoteArrangement.Center,
    ) {
      RemoteText(
        text = LiveValues.attribute(data.entityId, "media_title", data.title),
        color = theme.primaryText.rc,
        fontSize = 14.rsp,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      RemoteText(
        text = data.playerName.rs,
        color = theme.secondaryText.rc,
        fontSize = 11.rsp,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    // Non-clickable play/pause glyph (not a TransportButton): the
    // same RemoteAction captured in both breakpoint branches blanks
    // the Full tier in alpha010, and a tiny chip is a glance target —
    // the tap opens the full card.
    RemoteIcon(
      imageVector = if (data.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
      contentDescription = "play/pause".rs,
      modifier = RemoteModifier.size(22.rdp),
      tint = data.accent.rc,
    )
  }
}

@Composable
private fun TransportButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  action: HaAction,
  accent: androidx.compose.ui.graphics.Color,
  big: Boolean = false,
) {
  val click = action.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val size = if (big) 40.rdp else 32.rdp
  val iconSize = if (big) 22.rdp else 18.rdp
  RemoteBox(
    modifier = RemoteModifier.then(click).size(size).clip(RemoteCircleShape),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteIcon(
      imageVector = icon,
      contentDescription = "transport".rs,
      modifier = RemoteModifier.size(iconSize),
      tint = accent.rc,
    )
  }
}

@Composable
private fun ProgressBar(
  fraction: Float,
  accent: androidx.compose.ui.graphics.Color,
  theme: HaTheme,
) {
  val f = fraction.coerceIn(0f, 1f)
  RemoteBox(
    modifier =
      RemoteModifier.fillMaxWidth()
        .height(3.rdp)
        .padding(top = 4.rdp)
        .clip(RemoteRoundedCornerShape(1.rdp))
        .background(theme.divider.rc)
  ) {
    RemoteBox(modifier = RemoteModifier.fillMaxWidth(f).height(3.rdp).background(accent.rc))
  }
}
