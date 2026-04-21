package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
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
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `glance` card — title header over a flow-row of compact entity
 * cells. Matches `hui-glance-card.ts` visually.
 */
@Composable
@RemoteComposable
fun RemoteHaGlance(data: HaGlanceData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 12.rdp, vertical = 10.rdp),
    ) {
        RemoteColumn {
            if (data.title != null) {
                RemoteText(
                    text = data.title,
                    color = theme.primaryText.rc,
                    fontSize = 15.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                )
                RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp))
            }
            // HA's glance spaces cells evenly across the card width.
            // FlowRow handles wrapping when more cells arrive than fit
            // the row; within each row, distribute the cells using
            // SpaceEvenly instead of packing them to the start.
            RemoteFlowRow(
                modifier = RemoteModifier.fillMaxWidth(),
                horizontalArrangement = RemoteArrangement.SpaceEvenly,
                verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
            ) {
                data.cells.forEach { cell -> RemoteHaGlanceCell(cell) }
            }
        }
    }
}

/**
 * One cell inside a Glance — name on top, icon in the middle, state
 * below. Matches HA's `hui-glance-card.ts` cell order.
 */
@Composable
@RemoteComposable
fun RemoteHaGlanceCell(data: HaGlanceCellData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val clickable = data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val accent: RemoteColor = data.accent.isOn?.select(data.accent.activeAccent, data.accent.inactiveAccent)
        ?: data.accent.activeAccent
    RemoteColumn(
        modifier = modifier.then(clickable).padding(vertical = 4.rdp),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
        RemoteText(
            text = data.name,
            color = theme.primaryText.rc,
            fontSize = 12.rsp,
            style = RemoteTextStyle.Default,
        )
        RemoteBox(modifier = RemoteModifier.padding(vertical = 4.rdp)) {
            RemoteIcon(
                imageVector = data.icon,
                contentDescription = data.name,
                modifier = RemoteModifier.size(28.rdp),
                tint = accent,
            )
        }
        RemoteText(
            text = data.state,
            color = theme.secondaryText.rc,
            fontSize = 11.rsp,
            style = RemoteTextStyle.Default,
        )
    }
}
