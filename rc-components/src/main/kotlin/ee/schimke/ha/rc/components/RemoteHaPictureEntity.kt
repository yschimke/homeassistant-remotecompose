@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

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
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
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
 * HA `picture-entity` card — modern image-with-overlay tile
 * (`hui-picture-entity-card.ts`).
 *
 * ```
 *   ┌──────────────────────────────────┐
 *   │                                  │
 *   │              [icon]              │   ← image stub area
 *   │                                  │
 *   │  Name                  State     │   ← bottom translucent bar
 *   └──────────────────────────────────┘
 * ```
 *
 * RemoteCompose alpha08 doesn't accept arbitrary HTTP image references
 * at capture time, so the image area renders a flat tinted placeholder
 * with a domain-appropriate icon. The bottom bar matches HA's standard
 * `hui-image-overlay`.
 */
@Composable
@RemoteComposable
fun RemoteHaPictureEntity(
    data: HaPictureEntityData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    val accent = data.accent.isOn
        ?.select(data.accent.activeAccent, data.accent.inactiveAccent)
        ?: data.accent.activeAccent
    val clickable = data.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val showStrip = data.showName || data.showState

    RemoteBox(
        modifier = modifier
            .then(clickable)
            .fillMaxWidth()
            .height(160.rdp)
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.divider.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp)),
    ) {
        RemoteColumn(modifier = RemoteModifier.fillMaxSize()) {
            RemoteBox(
                modifier = RemoteModifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(accent.copy(alpha = accent.alpha * 0.10f.rf)),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = data.icon,
                    contentDescription = data.name,
                    modifier = RemoteModifier.size(40.rdp),
                    tint = accent.copy(alpha = accent.alpha * 0.55f.rf),
                )
            }
            if (showStrip) {
                val barBg = theme.cardBackground.rc
                RemoteRow(
                    modifier = RemoteModifier
                        .fillMaxWidth()
                        .background(barBg.copy(alpha = barBg.alpha * 0.85f.rf))
                        .padding(horizontal = 12.rdp, vertical = 8.rdp),
                    verticalAlignment = RemoteAlignment.CenterVertically,
                    horizontalArrangement = RemoteArrangement.SpaceBetween,
                ) {
                    RemoteText(
                        text = if (data.showName) data.name else "".rs,
                        color = theme.primaryText.rc,
                        fontSize = 13.rsp,
                        fontWeight = FontWeight.Medium,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (data.showState) {
                        RemoteText(
                            text = data.state,
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
    }
}
