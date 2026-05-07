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
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
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
 * `area` card — composite area tile with name, summary stat row, and
 * action chips (toggleable lights/covers/fans). HA's web card also
 * shows an optional area camera/picture; we don't render that yet
 * (alpha08 has no media-image channel into the document).
 */
@Composable
@RemoteComposable
fun RemoteHaArea(data: HaAreaCardData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
            RemoteText(
                text = data.name,
                color = theme.primaryText.rc,
                fontSize = 16.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (data.stats.isNotEmpty()) {
                RemoteRow(
                    modifier = RemoteModifier.fillMaxWidth(),
                    horizontalArrangement = RemoteArrangement.spacedBy(12.rdp),
                    verticalAlignment = RemoteAlignment.CenterVertically,
                ) {
                    data.stats.forEach { Stat(it, theme) }
                }
            }
            if (data.actions.isNotEmpty()) {
                RemoteRow(
                    modifier = RemoteModifier.fillMaxWidth().padding(top = 4.rdp),
                    horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
                ) {
                    data.actions.forEach { ActionChip(it, theme) }
                }
            }
        }
    }
}

@Composable
private fun Stat(stat: HaAreaStat, theme: HaTheme) {
    RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
        RemoteIcon(
            imageVector = stat.icon,
            contentDescription = stat.label,
            modifier = RemoteModifier.size(16.rdp),
            tint = theme.secondaryText.rc,
        )
        RemoteBox(modifier = RemoteModifier.padding(start = 6.rdp)) {
            RemoteText(
                text = stat.label,
                color = theme.secondaryText.rc,
                fontSize = 12.rsp,
                style = RemoteTextStyle.Default,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActionChip(action: HaAreaAction, theme: HaTheme) {
    val click = action.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val accent = action.accent.rc
    val bg = if (action.isActive) accent.copy(alpha = accent.alpha * 0.18f.rf)
    else theme.divider.rc.copy(alpha = theme.divider.rc.alpha * 0.4f.rf)
    RemoteBox(
        modifier = RemoteModifier.then(click)
            .size(40.rdp)
            .clip(RemoteCircleShape)
            .background(bg),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteIcon(
            imageVector = action.icon,
            contentDescription = "action".rs,
            modifier = RemoteModifier.size(20.rdp),
            tint = if (action.isActive) accent else theme.secondaryText.rc,
        )
    }
}
