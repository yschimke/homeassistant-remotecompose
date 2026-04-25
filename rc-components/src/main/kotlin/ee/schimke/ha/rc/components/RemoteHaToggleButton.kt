@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.CombinedAction
import androidx.compose.remote.creation.compose.action.ValueChange
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
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteBoolean
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * A toggleable variant of [RemoteHaButton].
 *
 * Mirrors the RemoteCompose SDK demo pattern (androidx-main
 * `compose/remote/integration-tests/demos/.../ClickableDemo.kt`):
 *
 * 1. `rememberMutableRemoteBoolean` creates local state *inside* the
 *    `.rc` document.
 * 2. A `ValueChange` action toggles that state on click — the player
 *    evaluates the write declaratively; no re-encoding needed.
 * 3. The accent color is bound to the same state via
 *    `isOn.select(active, inactive)` so the chip flip is instantaneous.
 * 4. [data.tapAction] still emits a `HostAction` alongside the local
 *    flip, so the host app can call HA's real service
 *    (`light.toggle`, `switch.toggle`, …) in response.
 *
 * If the host's service call fails, the host is responsible for
 * rolling back the optimistic state — it can do that by writing back
 * to the same binding.
 *
 * For non-toggleable entities pass [HaButtonData.accent.isOn] = null
 * and use the plain [RemoteHaButton] instead; this component expects
 * the optimistic model.
 */
@Composable
@RemoteComposable
fun RemoteHaToggleButton(data: HaButtonData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()

    // Optimistic in-doc state — seeded from the snapshot. The host
    // updates this same state when HA pushes a new entity state;
    // addressed by the in-document id of the MutableRemoteBoolean.
    val localIsOn = rememberMutableRemoteBoolean(data.accent.initiallyOn)

    // Build the click action(s): optimistic flip + optional host action.
    // alpha09's `clickable` takes a single Action — combine via CombinedAction.
    val toggle: Action = ValueChange(localIsOn, localIsOn.not())
    val host: Action? = data.tapAction.toRemoteAction()
    val click: Action = if (host != null) CombinedAction(toggle, host) else toggle
    val clickable = RemoteModifier.clickable(click)

    val accent: RemoteColor = localIsOn.select(data.accent.activeAccent, data.accent.inactiveAccent)

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
                    contentDescription = data.name,
                    modifier = RemoteModifier.size(28.rdp),
                    tint = accent,
                )
            }
            if (data.showName) {
                RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
                    RemoteText(
                        text = data.name,
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
