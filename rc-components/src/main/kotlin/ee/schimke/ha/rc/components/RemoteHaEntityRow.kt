package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * One row of an Entities card: [icon] [name] ................ [state].
 * Used as a building block by [RemoteHaEntities] and directly by the
 * `entity:` card type.
 */
@Composable
@RemoteComposable
fun RemoteHaEntityRow(data: HaEntityRowData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val clickable = data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val accent: RemoteColor = data.accent.isOn?.select(data.accent.activeAccent, data.accent.inactiveAccent)
        ?: data.accent.activeAccent
    RemoteRow(
        modifier = modifier.then(clickable).fillMaxWidth().padding(vertical = 6.rdp),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
        verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            RemoteIcon(
                imageVector = data.icon,
                contentDescription = data.name,
                modifier = RemoteModifier.size(20.rdp),
                tint = accent,
            )
            RemoteBox(modifier = RemoteModifier.padding(left = 12.rdp)) {
                RemoteText(
                    text = data.name,
                    color = theme.primaryText.rc,
                    fontSize = 13.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RemoteText(
            text = data.state,
            color = theme.secondaryText.rc,
            fontSize = 13.rsp,
            style = RemoteTextStyle.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
