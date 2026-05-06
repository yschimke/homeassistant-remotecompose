@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
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
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * `todo-list` card — title + active items section + completed items
 * section. Items are rendered as static rows; toggling a checkbox
 * inside the .rc document needs the todo-list service-call channel
 * (alpha08-compatible follow-up).
 */
@Composable
@RemoteComposable
fun RemoteHaTodoList(data: HaTodoListData, modifier: RemoteModifier = RemoteModifier) {
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
                text = data.title,
                color = theme.primaryText.rc,
                fontSize = 16.rsp,
                fontWeight = FontWeight.Medium,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (data.activeItems.isNotEmpty()) {
                Section("Active", theme)
                data.activeItems.forEach { Row(it, completed = false, theme = theme) }
            }
            if (data.completedItems.isNotEmpty()) {
                Section("Completed", theme)
                data.completedItems.forEach { Row(it, completed = true, theme = theme) }
            }
            if (data.activeItems.isEmpty() && data.completedItems.isEmpty()) {
                RemoteText(
                    text = "No items".rs,
                    color = theme.secondaryText.rc,
                    fontSize = 12.rsp,
                    style = RemoteTextStyle.Default,
                )
            }
        }
    }
}

@Composable
private fun Section(label: String, theme: HaTheme) {
    RemoteText(
        text = label.rs,
        color = theme.secondaryText.rc,
        fontSize = 11.rsp,
        fontWeight = FontWeight.Medium,
        style = RemoteTextStyle.Default,
    )
}

@Composable
private fun Row(label: androidx.compose.remote.creation.compose.state.RemoteString, completed: Boolean, theme: HaTheme) {
    RemoteRow(
        modifier = RemoteModifier.fillMaxWidth().padding(vertical = 4.rdp),
        verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
        RemoteIcon(
            imageVector = if (completed) Icons.Filled.CheckBox
            else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = label,
            modifier = RemoteModifier.size(18.rdp),
            tint = if (completed) theme.placeholderAccent.rc else theme.secondaryText.rc,
        )
        RemoteBox(modifier = RemoteModifier.padding(start = 10.rdp)) {
            RemoteText(
                text = label,
                color = if (completed) theme.secondaryText.rc else theme.primaryText.rc,
                fontSize = 13.rsp,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
