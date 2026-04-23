@file:Suppress("RestrictedApi")

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
 * HA `tile` card — modern primary-info card
 * (`home-assistant/frontend` → `hui-tile-card.ts`).
 *
 * ```
 *   ┌──────────────────────────────────┐
 *   │  [●] Name                        │
 *   │      State                       │
 *   └──────────────────────────────────┘
 * ```
 */
@Composable
@RemoteComposable
fun RemoteHaTile(data: HaTileData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val clickable = data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val accent: RemoteColor = data.accent.isOn?.select(data.accent.activeAccent, data.accent.inactiveAccent)
        ?: data.accent.activeAccent
    // Toggleable entities always get the chip (color varies with isOn);
    // read-only entities (no isOn binding) render icon plain.
    val renderChip: Boolean = data.accent.isOn != null

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
            if (renderChip) {
                RemoteBox(
                    modifier = RemoteModifier
                        .size(32.rdp)
                        .clip(RemoteCircleShape)
                        .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
                    contentAlignment = RemoteAlignment.Center,
                ) {
                    RemoteIcon(
                        imageVector = data.icon,
                        contentDescription = data.name,
                        modifier = RemoteModifier.size(20.rdp),
                        tint = accent,
                    )
                }
            } else {
                RemoteIcon(
                    imageVector = data.icon,
                    contentDescription = data.name,
                    modifier = RemoteModifier.size(24.rdp),
                    tint = accent,
                )
            }
            RemoteColumn(modifier = RemoteModifier.padding(left = 10.rdp)) {
                RemoteText(
                    text = data.name,
                    color = theme.primaryText.rc,
                    fontSize = 13.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RemoteText(
                    text = data.state,
                    color = theme.secondaryText.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

