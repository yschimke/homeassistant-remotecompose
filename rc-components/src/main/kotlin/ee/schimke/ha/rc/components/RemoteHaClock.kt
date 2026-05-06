@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * `clock` card — local time as a static text label captured at
 * encode time. The host re-encodes once a minute (or whatever cadence
 * the player uses); a future revision can swap this for a
 * `RemoteCanvas.drawText` bound to `RemoteAccess.getTime()` for
 * live-ticking without re-encode.
 */
@Composable
@RemoteComposable
fun RemoteHaClock(data: HaClockData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val timeSize = if (data.isLarge) 64 else 36
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteColumn(
            horizontalAlignment = RemoteAlignment.CenterHorizontally,
            verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) {
            if (data.title != null) {
                RemoteText(
                    text = data.title,
                    color = theme.secondaryText.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RemoteText(
                text = data.timeLabel,
                color = theme.primaryText.rc,
                fontSize = timeSize.rsp,
                fontWeight = FontWeight.Light,
                style = RemoteTextStyle.Default,
                maxLines = 1,
            )
            if (data.secondaryLabel != null) {
                RemoteText(
                    text = data.secondaryLabel,
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
