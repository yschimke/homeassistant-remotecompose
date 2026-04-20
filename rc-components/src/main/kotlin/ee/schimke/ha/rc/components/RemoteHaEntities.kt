package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * HA `entities` card — stacked list of [RemoteHaEntityRow]s with an
 * optional title header. Matches `hui-entities-card.ts` visually.
 */
@Composable
@RemoteComposable
fun RemoteHaEntities(
    title: RemoteString? = null,
    modifier: RemoteModifier = RemoteModifier,
    content: @Composable @RemoteComposable () -> Unit,
) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(16.rdp),
    ) {
        RemoteColumn(horizontalAlignment = RemoteAlignment.Start) {
            if (title != null) {
                RemoteText(
                    text = title,
                    color = theme.primaryText.rc,
                    fontSize = 18.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                )
                RemoteBox(modifier = RemoteModifier.padding(top = 4.rdp))
            }
            content()
        }
    }
}

