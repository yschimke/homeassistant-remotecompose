package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `button` card — large tappable surface with a centered icon and name.
 * Maps to `home-assistant/frontend` `hui-button-card.ts`.
 */
@Composable
@RemoteComposable
fun RemoteHaButton(
    name: RemoteString,
    icon: ImageVector,
    accent: RemoteColor,
    modifier: RemoteModifier = RemoteModifier,
    showName: Boolean = true,
    tapAction: HaAction = HaAction.None,
) {
    val theme = haTheme()
    val clickable = tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteBox(
        modifier = modifier
            .then(clickable)
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .padding(16.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteColumn(
            horizontalAlignment = RemoteAlignment.CenterHorizontally,
        ) {
            RemoteBox(
                modifier = RemoteModifier
                    .size(56.rdp)
                    .clip(RemoteRoundedCornerShape(28.rdp))
                    .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = icon,
                    contentDescription = name,
                    modifier = RemoteModifier.size(32.rdp),
                    tint = accent,
                )
            }
            if (showName) {
                RemoteBox(modifier = RemoteModifier.padding(top = 8.rdp)) {
                    RemoteText(
                        text = name,
                        color = theme.primaryText.rc,
                        fontSize = 14.rsp,
                        fontWeight = FontWeight.Medium,
                        style = RemoteTextStyle.Default,
                    )
                }
            }
        }
    }
}

