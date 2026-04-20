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
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * One row of an Entities card: [icon] [name] ................ [state].
 * Used as a building block by [RemoteHaEntities] and directly by the
 * `entity:` card type.
 */
@Composable
@RemoteComposable
fun RemoteHaEntityRow(
    name: RemoteString,
    state: RemoteString,
    icon: ImageVector,
    accent: RemoteColor,
    modifier: RemoteModifier = RemoteModifier,
    tapAction: HaAction = HaAction.None,
) {
    val theme = haTheme()
    val clickable = tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteRow(
        modifier = modifier.then(clickable).fillMaxWidth().padding(vertical = 8.rdp),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
        verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            RemoteIcon(
                imageVector = icon,
                contentDescription = name,
                modifier = RemoteModifier.size(24.rdp),
                tint = accent,
            )
            RemoteBox(modifier = RemoteModifier.padding(left = 16.rdp)) {
                RemoteText(
                    text = name,
                    color = theme.primaryText.rc,
                    fontSize = 14.rsp,
                    style = RemoteTextStyle.Default,
                )
            }
        }
        RemoteText(
            text = state,
            color = theme.secondaryText.rc,
            fontSize = 14.rsp,
            style = RemoteTextStyle.Default,
        )
    }
}

