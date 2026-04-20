package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
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
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * Icon treatment for a tile.
 *
 * HA's `hui-tile-card` draws a filled-circle behind the icon for entities
 * whose state is "active"-like (lights/switches on, locks unlocked, media
 * playing, etc). Sensors and other read-only domains render the icon
 * plain, without any chip. Same-domain entities swap between the two
 * based on state.
 */
enum class HaTileIconStyle {
    /** Icon glyph only, no circle behind it. Use for sensors and inactive states. */
    Plain,

    /** Icon centered inside a tinted-alpha circle. Use for active toggles. */
    Chip,
}

/**
 * Typed, reusable composable for Home Assistant's `tile` card — the modern
 * primary-info card (`home-assistant/frontend` → `hui-tile-card.ts`).
 *
 * ```
 *   ┌──────────────────────────────────┐
 *   │  [●] Name                        │
 *   │      State                       │
 *   └──────────────────────────────────┘
 * ```
 *
 * The caller pre-formats [state] (see `HaStateFormat.formatState`) and
 * picks [iconStyle] based on entity domain + state.
 */
@Composable
@RemoteComposable
fun RemoteHaTile(
    name: RemoteString,
    state: RemoteString,
    icon: ImageVector,
    accent: RemoteColor,
    modifier: RemoteModifier = RemoteModifier,
    iconStyle: HaTileIconStyle = HaTileIconStyle.Plain,
    tapAction: HaAction = HaAction.None,
) {
    val theme = haTheme()
    val clickable = tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteBox(
        modifier = modifier
            .then(clickable)
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 12.rdp, vertical = 8.rdp),
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            when (iconStyle) {
                HaTileIconStyle.Plain -> RemoteIcon(
                    imageVector = icon,
                    contentDescription = name,
                    modifier = RemoteModifier.size(24.rdp),
                    tint = accent,
                )
                HaTileIconStyle.Chip -> RemoteBox(
                    modifier = RemoteModifier
                        .size(32.rdp)
                        .clip(RemoteRoundedCornerShape(16.rdp))
                        .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
                    contentAlignment = RemoteAlignment.Center,
                ) {
                    RemoteIcon(
                        imageVector = icon,
                        contentDescription = name,
                        modifier = RemoteModifier.size(20.rdp),
                        tint = accent,
                    )
                }
            }
            RemoteColumn(modifier = RemoteModifier.padding(left = 10.rdp)) {
                RemoteText(
                    text = name,
                    color = theme.primaryText.rc,
                    fontSize = 13.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                )
                RemoteText(
                    text = state,
                    color = theme.secondaryText.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                )
            }
        }
    }
}
