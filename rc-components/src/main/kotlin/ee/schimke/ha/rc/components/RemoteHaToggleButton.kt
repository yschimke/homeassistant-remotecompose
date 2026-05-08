@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * A toggleable variant of [RemoteHaButton].
 *
 * The accent color tracks the entity's live `<entityId>.is_on` binding
 * (built from [HaButtonData.accent.initiallyOn] as the authoring-time
 * seed). On click we emit the configured [HaAction] — usually a
 * [HaAction.Toggle] [HostAction] — and the host writes the new value
 * back to the same binding. There is no in-document optimistic flip;
 * see `RemoteHaToggleSwitch` for the same model.
 *
 * For non-toggleable entities pass [HaToggleAccent.toggleable] = false
 * (or use the plain [RemoteHaButton]); without a live boolean the
 * accent stays on [activeAccent] unconditionally.
 */
@Composable
@RemoteComposable
fun RemoteHaToggleButton(data: HaButtonData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()

    // Live host binding for the entity's on/off state. Seeded from the
    // snapshot; the host pushes updates by name (`<entityId>.is_on`).
    val isOnBinding =
        if (data.accent.toggleable) LiveValues.isOn(data.entityId, data.accent.initiallyOn) else null

    // Click action is just the configured host action. No local state
    // flip — the accent updates when the host writes back to is_on.
    val clickable = data.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier

    // Tween the accent between inactive ↔ active so a host writeback
    // crossfades the icon halo and tint instead of snapping. Falls back
    // to the active accent unconditionally when there's no live binding
    // (preview / non-toggleable / null entityId).
    val accent: RemoteColor = if (isOnBinding != null) {
        val accentProgress: RemoteFloat = animateRemoteFloat(
            isOnBinding.select(1f.rf, 0f.rf),
            durationSeconds = 0.20f,
        )
        tween(data.accent.inactiveAccent, data.accent.activeAccent, accentProgress)
    } else {
        data.accent.activeAccent
    }

    // Wrap-content by default so grid / horizontal-stack can pack
    // multiple buttons per row. Standalone callers wanting a
    // card-wide button pass `RemoteModifier.fillMaxWidth()`.
    RemoteBox(
        modifier = modifier
            .then(clickable)
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp))
            .padding(horizontal = 12.rdp, vertical = 12.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
            RemoteBox(
                modifier = RemoteModifier
                    .size(48.rdp)
                    .clip(RemoteCircleShape)
                    .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = data.icon,
                    contentDescription = data.name.rs,
                    modifier = RemoteModifier.size(28.rdp),
                    tint = accent,
                )
            }
            if (data.showName) {
                RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
                    RemoteText(
                        text = data.name.rs,
                        color = theme.primaryText.rc,
                        fontSize = 13.rsp,
                        fontWeight = FontWeight.Medium,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
