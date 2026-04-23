@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * Visible placeholder for card types we haven't implemented yet. Keeps
 * the dashboard renderable instead of silently dropping unknown cards.
 */
@Composable
@RemoteComposable
fun RemoteHaUnsupported(data: HaUnsupportedData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.placeholderBackground.rc)
            .border(1.rdp, theme.placeholderAccent.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(12.rdp),
        contentAlignment = RemoteAlignment.CenterStart,
    ) {
        RemoteColumn {
            RemoteIcon(
                imageVector = Icons.Filled.Widgets,
                contentDescription = "Unsupported card".rs,
                modifier = RemoteModifier.size(24.rdp),
                tint = theme.placeholderAccent.rc,
            )
            RemoteBox(modifier = RemoteModifier.padding(top = 8.rdp)) {
                RemoteText(
                    text = "Not yet supported".rs,
                    color = theme.primaryText.rc,
                    fontSize = 14.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                )
            }
            RemoteText(
                text = data.cardType,
                color = theme.secondaryText.rc,
                fontSize = 12.rsp,
                style = RemoteTextStyle.Default,
            )
        }
    }
}
