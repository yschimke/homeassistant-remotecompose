package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.padding
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
 * HA `heading` card — section title inside a Sections view. Matches
 * `hui-heading-card.ts`. Three sizes: H1 (default), H2, H3.
 */
@Composable
@RemoteComposable
fun RemoteHaHeading(
    title: RemoteString,
    modifier: RemoteModifier = RemoteModifier,
    style: HaHeadingStyle = HaHeadingStyle.Title,
) {
    val theme = haTheme()
    RemoteText(
        text = title,
        modifier = modifier.padding(vertical = 8.rdp),
        color = theme.primaryText.rc,
        fontSize = style.sizeSp.rsp,
        fontWeight = style.weight,
        style = RemoteTextStyle.Default,
    )
}

enum class HaHeadingStyle(val sizeSp: Int, val weight: FontWeight) {
    Title(22, FontWeight.Medium),
    Subtitle(16, FontWeight.Medium),
}
