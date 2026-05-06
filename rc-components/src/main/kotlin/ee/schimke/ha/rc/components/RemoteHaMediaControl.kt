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
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
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
 * `media-control` card — title/artist + transport buttons + a position
 * bar. Album art renders as a tinted music-note placeholder; pulling
 * arbitrary HTTP image entities into the .rc document needs a media
 * channel alpha08 doesn't expose.
 */
@Composable
@RemoteComposable
fun RemoteHaMediaControl(data: HaMediaControlData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 12.rdp, vertical = 12.rdp),
    ) {
        RemoteRow(
            modifier = RemoteModifier.fillMaxWidth(),
            verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
            // Body
            RemoteColumn(modifier = RemoteModifier.weight(1f).padding(end = 12.rdp)) {
                RemoteText(
                    text = data.playerName,
                    color = data.accent.rc,
                    fontSize = 11.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RemoteText(
                    text = data.title,
                    color = theme.primaryText.rc,
                    fontSize = 16.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (data.artist != null) {
                    RemoteText(
                        text = data.artist,
                        color = theme.secondaryText.rc,
                        fontSize = 12.rsp,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                ProgressBar(data.positionFraction, data.accent, theme)
                if (data.positionLabel != null && data.durationLabel != null) {
                    RemoteRow(
                        modifier = RemoteModifier.fillMaxWidth(),
                        horizontalArrangement = RemoteArrangement.SpaceBetween,
                    ) {
                        RemoteText(
                            text = data.positionLabel,
                            color = theme.secondaryText.rc,
                            fontSize = 11.rsp,
                            style = RemoteTextStyle.Default,
                        )
                        RemoteText(
                            text = data.durationLabel,
                            color = theme.secondaryText.rc,
                            fontSize = 11.rsp,
                            style = RemoteTextStyle.Default,
                        )
                    }
                }
            }
            // Album-art placeholder
            RemoteBox(
                modifier = RemoteModifier
                    .size(96.rdp)
                    .clip(RemoteRoundedCornerShape(8.rdp))
                    .background(data.accent.rc.copy(alpha = data.accent.rc.alpha * 0.15f.rf)),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = data.title,
                    modifier = RemoteModifier.size(40.rdp),
                    tint = data.accent.rc,
                )
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: HaAction,
    accent: androidx.compose.ui.graphics.Color,
    big: Boolean = false,
) {
    val click = action.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val size = if (big) 40.rdp else 32.rdp
    val iconSize = if (big) 22.rdp else 18.rdp
    RemoteBox(
        modifier = RemoteModifier.then(click)
            .size(size)
            .clip(RemoteCircleShape),
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
private fun ProgressBar(fraction: Float, accent: androidx.compose.ui.graphics.Color, theme: HaTheme) {
    val f = fraction.coerceIn(0f, 1f)
    RemoteBox(
        modifier = RemoteModifier
            .fillMaxWidth()
            .height(3.rdp)
            .padding(top = 4.rdp)
            .clip(RemoteRoundedCornerShape(1.rdp))
            .background(theme.divider.rc),
    ) {
        RemoteBox(
            modifier = RemoteModifier
                .fillMaxWidth(f)
                .height(3.rdp)
                .background(accent.rc),
        )
    }
}
