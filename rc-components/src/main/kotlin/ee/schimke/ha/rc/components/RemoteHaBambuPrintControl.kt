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
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * `custom:ha-bambulab-print_control-card` — three-button pad that maps
 * to the printer's pause / resume / stop button entities.
 */
@Composable
@RemoteComposable
fun RemoteHaBambuPrintControl(
    data: HaBambuPrintControlData,
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
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
            RemoteText(
                text = data.printerName,
                color = theme.secondaryText.rc,
                fontSize = 11.rsp,
                style = RemoteTextStyle.Default,
            )
            RemoteRow(
                modifier = RemoteModifier.fillMaxWidth(),
                horizontalArrangement = RemoteArrangement.SpaceEvenly,
                verticalAlignment = RemoteAlignment.CenterVertically,
            ) {
                listOfNotNull(data.pause, data.resume, data.stop).forEach { btn ->
                    Button(btn, theme)
                }
            }
        }
    }
}

@Composable
private fun Button(button: HaBambuControlButton, theme: HaTheme) {
    val click = button.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteColumn(
        modifier = RemoteModifier.then(click),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
        val accent = button.accent.rc
        RemoteBox(
            modifier = RemoteModifier
                .size(48.rdp)
                .clip(RemoteCircleShape)
                .background(accent.copy(alpha = accent.alpha * 0.18f.rf)),
            contentAlignment = RemoteAlignment.Center,
        ) {
            RemoteIcon(
                imageVector = button.icon,
                contentDescription = button.label,
                modifier = RemoteModifier.size(24.rdp),
                tint = accent,
            )
        }
        RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
            RemoteText(
                text = button.label,
                color = theme.primaryText.rc,
                fontSize = 12.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
            )
        }
    }
}
