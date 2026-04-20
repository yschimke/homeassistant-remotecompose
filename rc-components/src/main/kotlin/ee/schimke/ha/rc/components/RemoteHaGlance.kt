package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
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
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `glance` card — a title header over a flow-row of compact entity
 * cells (icon + state or name). Matches `hui-glance-card.ts` visually.
 */
@Composable
@RemoteComposable
fun RemoteHaGlance(
    title: RemoteString? = null,
    modifier: RemoteModifier = RemoteModifier,
    content: @Composable @RemoteComposable () -> Unit,
) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .padding(16.rdp),
    ) {
        RemoteColumn {
            if (title != null) {
                RemoteText(
                    text = title,
                    color = theme.primaryText.rc,
                    fontSize = 18.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                )
                RemoteBox(modifier = RemoteModifier.padding(top = 8.rdp))
            }
            RemoteFlowRow(
                horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
                verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
            ) {
                content()
            }
        }
    }
}

/** One cell inside a Glance. */
@Composable
@RemoteComposable
fun RemoteHaGlanceCell(
    name: RemoteString,
    state: RemoteString,
    icon: ImageVector,
    accent: RemoteColor,
    modifier: RemoteModifier = RemoteModifier,
    tapAction: HaAction = HaAction.None,
) {
    val theme = haTheme()
    val clickable = tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteColumn(
        modifier = modifier.then(clickable).padding(4.rdp),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
        RemoteIcon(
            imageVector = icon,
            contentDescription = name,
            modifier = RemoteModifier.size(32.rdp),
            tint = accent,
        )
        RemoteBox(modifier = RemoteModifier.padding(top = 4.rdp)) {
            RemoteText(
                text = name,
                color = theme.primaryText.rc,
                fontSize = 12.rsp,
                style = RemoteTextStyle.Default,
            )
        }
        RemoteText(
            text = state,
            color = theme.secondaryText.rc,
            fontSize = 11.rsp,
            style = RemoteTextStyle.Default,
        )
    }
}

