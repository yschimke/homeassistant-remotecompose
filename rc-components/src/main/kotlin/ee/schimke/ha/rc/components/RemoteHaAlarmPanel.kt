@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
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
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * `alarm-panel` card — title, status badge, ARM action buttons, and
 * the numeric keypad (matches HA's reference). Each keypad key fires
 * its own [HaAction.AlarmKey] host action; the host's
 * `AlarmKeypadCoordinator` accumulates the keys and submits a
 * combined `alarm_control_panel.alarm_*` call once the user finishes
 * an attempt. The .rc document carries no in-band buffer.
 */
@Composable
@RemoteComposable
fun RemoteHaAlarmPanel(data: HaAlarmPanelData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val statusByKey = data.statuses.associateBy { it.stateKey }
    val initialStatus = statusByKey[data.initialStateInt] ?: data.statuses.first()
    // Keypad accent stays tied to the initial state — keypad chrome is
    // permanent at capture time, the live binding only swaps the status
    // chrome above.
    val keypadAccent = initialStatus.accent
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 14.rdp, vertical = 12.rdp),
    ) {
        RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(10.rdp)) {
            StatusRow(data, statusByKey, initialStatus, theme)

            // ARM AWAY / ARM HOME / DISARM buttons.
            if (data.actions.isNotEmpty()) {
                RemoteRow(
                    modifier = RemoteModifier.fillMaxWidth(),
                    horizontalArrangement = RemoteArrangement.spacedBy(8.rdp, RemoteAlignment.CenterHorizontally),
                ) {
                    data.actions.forEach { action ->
                        ActionPill(action, theme)
                    }
                }
            }

            if (data.showKeypad) Keypad(data.entityId, keypadAccent, theme)
        }
    }
}

@Composable
@RemoteComposable
private fun StatusRow(
    data: HaAlarmPanelData,
    statusByKey: Map<Int, HaAlarmStatus>,
    initialStatus: HaAlarmStatus,
    theme: HaTheme,
) {
    val keys = data.statuses.map { it.stateKey }.toIntArray()
    val stateInt = LiveValues.intState(data.entityId, data.initialStateInt)
    RemoteStateLayout(stateInt, *keys) { key ->
        val status = statusByKey[key] ?: initialStatus
        RemoteRow(
            modifier = RemoteModifier.fillMaxWidth(),
            verticalAlignment = RemoteAlignment.CenterVertically,
            horizontalArrangement = RemoteArrangement.SpaceBetween,
        ) {
            RemoteColumn {
                RemoteText(
                    text = data.title.rs,
                    color = theme.primaryText.rc,
                    fontSize = 16.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RemoteText(
                    text = status.label.rs,
                    color = status.accent.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
            }
            RemoteBox(
                modifier = RemoteModifier
                    .size(40.rdp)
                    .clip(RemoteCircleShape)
                    .border(2.rdp, status.accent.rc, RemoteCircleShape),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = status.icon,
                    contentDescription = status.label.rs,
                    modifier = RemoteModifier.size(22.rdp),
                    tint = status.accent.rc,
                )
            }
        }
    }
}

@Composable
private fun ActionPill(action: HaAlarmAction, theme: HaTheme) {
    val click = action.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val accent = action.accent.rc
    RemoteBox(
        modifier = RemoteModifier.then(click)
            .clip(RemoteRoundedCornerShape(6.rdp))
            .background(accent.copy(alpha = accent.alpha * 0.0f.rf))
            .border(1.rdp, accent, RemoteRoundedCornerShape(6.rdp))
            .padding(horizontal = 14.rdp, vertical = 8.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteText(
            text = action.label.rs,
            color = accent,
            fontSize = 12.rsp,
            fontWeight = FontWeight.Medium,
            style = RemoteTextStyle.Default,
            maxLines = 1,
        )
    }
}

@Composable
private fun Keypad(
    entityId: String?,
    accent: androidx.compose.ui.graphics.Color,
    theme: HaTheme,
) {
    RemoteColumn(
        modifier = RemoteModifier.fillMaxWidth().padding(top = 6.rdp),
        verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
    ) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            .forEach { row ->
                RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
                    row.forEach { KeypadKey(entityId, it, it, accent, theme) }
                }
            }
        RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
            RemoteBox(modifier = RemoteModifier.size(48.rdp))
            KeypadKey(entityId, "0", "0", accent, theme)
            KeypadKey(entityId, "⌫", "backspace", accent, theme)
        }
    }
}

@Composable
private fun KeypadKey(
    entityId: String?,
    label: String,
    key: String,
    accent: androidx.compose.ui.graphics.Color,
    theme: HaTheme,
) {
    val click = entityId
        ?.let { HaAction.AlarmKey(it, key).toRemoteAction() }
        ?.let { RemoteModifier.clickable(it) }
        ?: RemoteModifier
    RemoteBox(
        modifier = RemoteModifier.then(click)
            .size(48.rdp)
            .clip(RemoteRoundedCornerShape(6.rdp))
            .border(1.rdp, accent.rc, RemoteRoundedCornerShape(6.rdp)),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteText(
            text = label.rs,
            color = accent.rc,
            fontSize = 16.rsp,
            fontWeight = FontWeight.Medium,
            style = RemoteTextStyle.Default,
            maxLines = 1,
        )
    }
}
