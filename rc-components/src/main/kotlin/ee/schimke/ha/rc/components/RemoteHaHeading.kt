@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * HA `heading` card — section title inside a Sections view. Matches
 * `hui-heading-card.ts`.
 */
@Composable
@RemoteComposable
fun RemoteHaHeading(data: HaHeadingData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteText(
        text = data.title,
        modifier = modifier.padding(vertical = 8.rdp),
        color = theme.primaryText.rc,
        fontSize = data.style.sizeSp.rsp,
        fontWeight = data.style.weight,
        style = RemoteTextStyle.Default,
    )
}

enum class HaHeadingStyle(val sizeSp: Int, val weight: FontWeight) {
    Title(22, FontWeight.Medium),
    Subtitle(16, FontWeight.Medium),
}
