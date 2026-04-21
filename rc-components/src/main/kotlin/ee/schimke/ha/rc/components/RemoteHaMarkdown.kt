package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * HA `markdown` card — renders plain text lines inside the card chrome.
 * A small subset of Markdown (heading `#`, bold `**`) could be parsed
 * later; today we keep it readable without re-implementing a parser.
 */
@Composable
@RemoteComposable
fun RemoteHaMarkdown(data: HaMarkdownData, modifier: RemoteModifier = RemoteModifier) {
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
                RemoteBox(modifier = RemoteModifier.padding(top = 4.rdp))
            }
            data.lines.forEach { line ->
                RemoteText(
                    text = line,
                    color = theme.primaryText.rc,
                    fontSize = 13.rsp,
                    style = RemoteTextStyle.Default,
                )
            }
        }
    }
}
