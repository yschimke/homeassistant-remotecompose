@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
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
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxHeight
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.offset
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable

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
  if (haLayouts().media != HaMediaLayout.ArtBeside) {
    CommunityMediaPanel(data, modifier, fillHeight)
    return
  }
  val theme = haTheme()
  RemoteBox(
    modifier =
      modifier
        .then(if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth())
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 12.rdp),
  ) {
    // Box→Column→Row, never Box→Row: a fillMaxSize Row directly under a
    // wrap-content Box (this card's root, or the breakpoint's
    // immediateSwap wrapper in Fixed mode) captures blank in alpha010;
    // the Column buffer fixes it (see § Known gaps #9).
    RemoteColumn(
      modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth(),
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
            if (fillHeight) RemoteModifier.weight(1f.rf).fillMaxHeight().padding(end = 12.rdp)
            else RemoteModifier.weight(1f.rf).padding(end = 12.rdp),
        ) {
          TitleBlock(data, theme)
          if (fillHeight) RemoteBox(modifier = RemoteModifier.weight(1f.rf))
          TransportRow(data)
          if (fillHeight) RemoteBox(modifier = RemoteModifier.weight(1f.rf))
          SeekBlock(data, theme)
        }
        // Album-art placeholder, vertically centred beside the body.
        RemoteBox(
          modifier =
            RemoteModifier.size(96.rdp)
              .clip(haControlShape(8))
              .background(
                if (haLayouts().artwork == HaArtwork.TapedSlot) ConsoleSlot.rc
                else haInk(data.accent).copy(alpha = haInk(data.accent).alpha * 0.15f.rf),
              ),
          contentAlignment = RemoteAlignment.Center,
        ) {
          if (haLayouts().artwork == HaArtwork.TapedSlot)
            ConsoleTapeLabel("No artwork".rs, RemoteModifier.padding(6.rdp))
          else
            RemoteBasicIcon(
              imageVector = Icons.Filled.MusicNote.toRemoteImageVector(),
              contentDescription = LiveValues.attribute(data.entityId, "media_title", data.title),
              modifier = RemoteModifier.size(40.rdp),
              tint = haInk(data.accent),
            )
        }
      }
    }
  }
}

@Composable
private fun TitleBlock(data: HaMediaControlData, theme: RemoteHaTheme) {
  RemoteText(
    text = entityName(data.entityId, data.playerName),
    color = haInk(data.accent),
    fontSize = HaType.Caption,
    fontWeight = RemoteFontWeight.Medium,
    style = haTextStyle(),
    maxLines = 1,
    overflow = RemoteTextOverflow.Ellipsis,
  )
  RemoteText(
    text = LiveValues.attribute(data.entityId, "media_title", data.title),
    color = theme.primaryText,
    fontSize = HaType.Title,
    fontWeight = RemoteFontWeight.Medium,
    style = haTextStyle(),
    maxLines = 1,
    overflow = RemoteTextOverflow.Ellipsis,
  )
  if (data.artist != null) {
    RemoteText(
      text = LiveValues.attribute(data.entityId, "media_artist", data.artist),
      color = theme.secondaryText,
      fontSize = HaType.Label,
      style = haTextStyle(),
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun TransportRow(data: HaMediaControlData) {
  // Each button's 48dp touch target is wider and taller than the button drawn in it. The targets
  // sit edge to edge, so no tap is shared between two buttons (the accessibility checker counts
  // only
  // a target's unshared area): the drawn buttons end up 12dp apart. The row's offset, in place of
  // the 6dp top inset, keeps the first button where it was. The row ends up 2dp taller.
  RemoteRow(
    modifier = RemoteModifier.offset((-8).rdp, 2.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    TransportButton(Icons.Filled.SkipPrevious, "Previous", data.previousAction, data.accent)
    TransportButton(
      if (data.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
      if (data.isPlaying) "Pause" else "Play",
      data.playPauseAction,
      data.accent,
      big = true,
    )
    TransportButton(Icons.Filled.SkipNext, "Next", data.nextAction, data.accent)
  }
}

@Composable
private fun SeekBlock(data: HaMediaControlData, theme: RemoteHaTheme) {
  ProgressBar(data.entityId, data.positionFraction, data.accent, theme)
  if (data.positionLabel != null && data.durationLabel != null) {
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth(),
      horizontalArrangement = RemoteArrangement.SpaceBetween,
    ) {
      RemoteText(
        text = LiveValues.attribute(data.entityId, "media_position_label", data.positionLabel),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
      )
      RemoteText(
        text = LiveValues.attribute(data.entityId, "media_duration_label", data.durationLabel),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
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
        // 2dp, not more: the Wear small slot's content box is ~44dp, and the title (BodyLarge)
        // over the player (Caption) is ~37dp of it (lines are ~1.33× their size). The row centres
        // itself on taller cells.
        .padding(horizontal = 10.rdp, vertical = 2.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    // Gaps are padding on the weighted column, not spacedBy: the player shares a weighted row's
    // width out before spacing, so spacedBy pushed the play glyph off a narrow cell's edge.
    RemoteBox(
      modifier =
        RemoteModifier.size(40.rdp)
          .clip(haControlShape(6))
          .background(
            if (haLayouts().artwork == HaArtwork.TapedSlot) ConsoleSlot.rc
            else haInk(data.accent).copy(alpha = haInk(data.accent).alpha * 0.15f.rf),
          ),
      contentAlignment = RemoteAlignment.Center,
    ) {
      if (haLayouts().artwork == HaArtwork.TapedSlot) ConsoleTapeLabel("No art".rs, compact = true)
      else
        RemoteBasicIcon(
          imageVector = Icons.Filled.MusicNote.toRemoteImageVector(),
          contentDescription = LiveValues.attribute(data.entityId, "media_title", data.title),
          modifier = RemoteModifier.size(22.rdp),
          tint = haInk(data.accent),
        )
    }
    RemoteColumn(
      modifier = RemoteModifier.weight(1f.rf).padding(horizontal = 10.rdp),
      verticalArrangement = RemoteArrangement.Center,
    ) {
      RemoteText(
        text = LiveValues.attribute(data.entityId, "media_title", data.title),
        color = theme.primaryText,
        fontSize = HaType.BodyLarge,
        fontWeight = RemoteFontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      RemoteText(
        text = entityName(data.entityId, data.playerName),
        color = theme.secondaryText,
        fontSize = HaType.Caption,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
    }
    // Non-clickable play/pause glyph (not a TransportButton): the
    // same RemoteAction captured in both breakpoint branches blanks
    // the Full tier in alpha010, and a tiny chip is a glance target —
    // the tap opens the full card.
    transportKeyRenderer()
      .Glyph(
        if (data.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        data.accent,
        "play/pause".rs,
      )
  }
}

/**
 * A transport control drawn at its own size (40dp play/pause, 32dp skip) with a full touch target
 * around it ([RemoteHaTouchTarget]), labelled with what it does.
 */
@Composable
private fun TransportButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  action: HaAction,
  accent: androidx.compose.ui.graphics.Color,
  big: Boolean = false,
) {
  val size = if (big) 40 else 32
  // The key's face is the theme's [HaComponentSlot.TransportKey]: Console's key with a pilot lamp,
  // IKEA's moulded key, a kit's rim, or the bare icon.
  RemoteHaTouchTarget(size, size, action.toRemoteAction(), label.rs) {
    transportKeyRenderer().Key(HaTransportSpec(icon, accent, size, if (big) 22 else 18))
  }
}

@Composable
private fun ProgressBar(
  entityId: String?,
  fraction: Float,
  accent: androidx.compose.ui.graphics.Color,
  theme: RemoteHaTheme,
) {
  // `<entityId>.media_position_fraction`: constant in a live capture, bound in a design template.
  val f = entityFloat(entityId, "media_position_fraction", fraction.coerceIn(0f, 1f))
  RemoteBox(
    modifier =
      RemoteModifier.fillMaxWidth()
        .padding(top = 4.rdp)
        .height(3.rdp)
        .clip(RemoteRoundedCornerShape(1.rdp))
        .background(theme.divider),
  ) {
    RemoteBox(modifier = RemoteModifier.fillMaxWidth(f).height(3.rdp).background(haInk(accent)))
  }
}

/** Distinct control-centre, landscape and cinema compositions share the original live controls. */
@Composable
private fun CommunityMediaPanel(
  data: HaMediaControlData,
  modifier: RemoteModifier,
  fillHeight: Boolean,
) {
  val theme = haTheme()
  val layout = haLayouts().media
  val artwork: @Composable (RemoteModifier) -> Unit = { artModifier ->
    RemoteBox(
      artModifier.clip(haControlShape(16)).background(theme.placeholderBackground),
      contentAlignment = RemoteAlignment.Center,
    ) {
      RemoteBasicIcon(
        Icons.Filled.MusicNote.toRemoteImageVector(),
        contentDescription = "No artwork".rs,
        modifier = RemoteModifier.size(32.rdp),
        tint = haInk(data.accent),
      )
    }
  }
  RemoteColumn(
    modifier
      .then(if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth())
      .then(cardChrome(theme.cardBackground, theme.divider))
      .padding(14.rdp),
  ) {
    when (layout) {
      HaMediaLayout.ControlCentre -> {
        RemoteRow(
          RemoteModifier.fillMaxWidth(),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          artwork(RemoteModifier.size(60.rdp))
          RemoteColumn(RemoteModifier.weight(1f.rf).padding(start = 12.rdp)) {
            TitleBlock(data, theme)
          }
        }
        if (fillHeight) RemoteBox(RemoteModifier.weight(1f.rf))
        RemoteRow(RemoteModifier.fillMaxWidth(), horizontalArrangement = RemoteArrangement.Center) {
          TransportRow(data)
        }
        SeekBlock(data, theme)
      }
      HaMediaLayout.Landscape -> {
        TitleBlock(data, theme)
        if (fillHeight) RemoteBox(RemoteModifier.weight(1f.rf))
        RemoteRow(
          RemoteModifier.fillMaxWidth(),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          RemoteBox(RemoteModifier.weight(1f.rf)) { TransportRow(data) }
          artwork(RemoteModifier.size(52.rdp))
        }
        SeekBlock(data, theme)
      }
      else -> {
        artwork(RemoteModifier.fillMaxWidth().height(84.rdp))
        TitleBlock(data, theme)
        if (fillHeight) RemoteBox(RemoteModifier.weight(1f.rf))
        RemoteRow(RemoteModifier.fillMaxWidth(), horizontalArrangement = RemoteArrangement.Center) {
          TransportRow(data)
        }
        SeekBlock(data, theme)
      }
    }
  }
}
