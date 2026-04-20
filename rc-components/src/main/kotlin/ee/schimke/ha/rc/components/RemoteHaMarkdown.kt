package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
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
 * HA `markdown` card — we currently render plain text lines. A small subset
 * of Markdown (heading `#`, bold `**`, newlines) could be added later; for
 * now we keep it readable without re-implementing a parser.
 */
@Composable
@RemoteComposable
fun RemoteHaMarkdown(
    lines: List<RemoteString>,
    title: RemoteString? = null,
    modifier: RemoteModifier = RemoteModifier,
) {
    RemoteBox(
        modifier = modifier
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(Color.White.rc)
            .padding(16.rdp),
    ) {
        RemoteColumn {
            if (title != null) {
                RemoteText(
                    text = title,
                    color = Color(0xFF1C1C1C).rc,
                    fontSize = 18.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                )
                RemoteBox(modifier = RemoteModifier.padding(top = 8.rdp))
            }
            lines.forEach { line ->
                RemoteText(
                    text = line,
                    color = Color(0xFF1C1C1C).rc,
                    fontSize = 14.rsp,
                    style = RemoteTextStyle.Default,
                )
            }
        }
    }
}
