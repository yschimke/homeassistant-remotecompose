package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
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
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

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
 * The caller pre-formats [state] — use `HaStateFormat.formatState(entity)`
 * on the converter side, which capitalizes boolean states, attaches units
 * to numeric states, and falls back to "Unavailable".
 */
@Composable
@androidx.compose.remote.creation.compose.layout.RemoteComposable
fun RemoteHaTile(
    name: RemoteString,
    state: RemoteString,
    icon: ImageVector,
    accent: RemoteColor,
    modifier: RemoteModifier = RemoteModifier,
    tapAction: HaAction = HaAction.None,
) {
    val clickable = tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteBox(
        modifier = modifier
            .then(clickable)
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(Color.White.rc)
            .padding(12.rdp),
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            RemoteBox(
                modifier = RemoteModifier
                    .size(40.rdp)
                    .clip(RemoteRoundedCornerShape(20.rdp))
                    .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = icon,
                    contentDescription = name,
                    modifier = RemoteModifier.size(24.rdp),
                    tint = accent,
                )
            }
            RemoteColumn(modifier = RemoteModifier.padding(left = 12.rdp)) {
                RemoteText(
                    text = name,
                    color = COLOR_PRIMARY_TEXT,
                    fontSize = 14.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                )
                RemoteText(
                    text = state,
                    color = COLOR_SECONDARY_TEXT,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                )
            }
        }
    }
}

private val COLOR_PRIMARY_TEXT: RemoteColor get() = Color(0xFF1C1C1C).rc
private val COLOR_SECONDARY_TEXT: RemoteColor get() = Color(0xFF5F6367).rc
