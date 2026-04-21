package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `button` card — tappable card with a centered icon chip and name.
 * Maps to `home-assistant/frontend` `hui-button-card.ts`.
 */
@Composable
@RemoteComposable
fun RemoteHaButton(data: HaButtonData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val clickable = data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val accent: RemoteColor = data.accent.isOn?.select(data.accent.activeAccent, data.accent.inactiveAccent)
        ?: data.accent.activeAccent
    // Not using `fillMaxWidth()`: buttons placed in a grid or
    // horizontal-stack should wrap-content so the flow layout can
    // pack multiple across the row. Standalone callers wanting a
    // card-wide button can pass `RemoteModifier.fillMaxWidth()`.
    RemoteBox(
        modifier = modifier
            .then(clickable)
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 12.rdp, vertical = 12.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
            RemoteBox(
                modifier = RemoteModifier
                    .size(48.rdp)
                    .clip(RemoteCircleShape)
                    .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = data.icon,
                    contentDescription = data.name,
                    modifier = RemoteModifier.size(28.rdp),
                    tint = accent,
                )
            }
            if (data.showName) {
                RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
                    RemoteText(
                        text = data.name,
                        color = theme.primaryText.rc,
                        fontSize = 13.rsp,
                        fontWeight = FontWeight.Medium,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
