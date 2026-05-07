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
import androidx.compose.ui.text.style.TextOverflow

/**
 * `statistic` card — single hero value (mean / min / max / sum) for one
 * entity, with optional period label + unit suffix.
 */
@Composable
@RemoteComposable
fun RemoteHaStatisticCard(
    data: HaStatisticCardData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(4.rdp)) {
            RemoteText(
                text = data.name,
                color = theme.secondaryText.rc,
                fontSize = 12.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RemoteRow(verticalAlignment = RemoteAlignment.Bottom) {
                RemoteText(
                    text = data.valueLabel,
                    color = theme.primaryText.rc,
                    fontSize = 32.rsp,
                    fontWeight = FontWeight.SemiBold,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
                if (data.unit != null) {
                    RemoteBox(modifier = RemoteModifier.padding(start = 4.rdp, bottom = 4.rdp)) {
                        RemoteText(
                            text = data.unit,
                            color = theme.secondaryText.rc,
                            fontSize = 14.rsp,
                            style = RemoteTextStyle.Default,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (data.periodLabel != null) {
                RemoteText(
                    text = data.periodLabel,
                    color = data.accent.rc,
                    fontSize = 11.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
            }
        }
    }
}
