@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.CombinedAction
import androidx.compose.remote.creation.compose.action.ValueChange
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.offset
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteBoolean
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.state.asRemoteDp
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * One row of an Entities card:
 *
 * ```
 *   [icon]  name  ..........  state-or-switch
 * ```
 *
 * Toggleable entities (lights / switches / locks / …) render a
 * [RemoteHaToggleSwitch] in the trailing slot — matches HA's
 * `hui-toggle-entity-row`. Read-only entities show the state text.
 */
@Composable
@RemoteComposable
fun RemoteHaEntityRow(data: HaEntityRowData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val clickable =
        data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val isOnBinding =
        if (data.accent.toggleable) LiveValues.isOn(data.entityId, data.accent.initiallyOn) else null
    val accent: RemoteColor =
        isOnBinding?.select(data.accent.activeAccent, data.accent.inactiveAccent)
            ?: data.accent.activeAccent

    RemoteRow(
        modifier = modifier.then(clickable).fillMaxWidth().padding(vertical = 6.rdp),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
        verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            RemoteIcon(
                imageVector = data.icon,
                contentDescription = data.name.rs,
                modifier = RemoteModifier.size(20.rdp),
                tint = accent,
            )
            RemoteBox(modifier = RemoteModifier.padding(start = 12.rdp)) {
                RemoteText(
                    text = data.name.rs,
                    color = theme.primaryText.rc,
                    fontSize = 13.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isOnBinding != null) {
            RemoteHaToggleSwitch(
                initiallyOn = data.accent.initiallyOn,
                activeAccent = data.accent.activeAccent,
                inactiveAccent = data.accent.inactiveAccent,
                tapAction = data.tapAction,
            )
        } else {
            RemoteText(
                text = LiveValues.state(data.entityId, data.state),
                color = theme.secondaryText.rc,
                fontSize = 13.rsp,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ——— toggle switch ———

/** Roughly Material 2's switch dimensions. */
private val TrackWidth = 36.rdp
private val TrackHeight = 22.rdp
private val KnobSize = 16.rdp
private const val KnobInsetDp = 3f
private val KnobInset = KnobInsetDp.toInt().rdp

/** Distance the knob travels in dp between off and on. */
private const val KnobTravelDp = 36f - 16f - 2f * KnobInsetDp // = 14f

/** Animation timings. */
private const val ToggleDurationSeconds = 0.20f

/**
 * Pill-shaped toggle switch — animates between off / on.
 *
 * 1. `rememberMutableRemoteBoolean` creates document-local state seeded
 *    from [initiallyOn].
 * 2. A `ValueChange` toggles that state on click; the player evaluates
 *    the write declaratively, no re-encoding required.
 * 3. The boolean drives a `select`-derived `RemoteFloat` progress in
 *    `[0, 1]`, wrapped in [animateRemoteFloat] so successive flips
 *    tween instead of snapping.
 * 4. The track color tweens between [inactiveAccent] and [activeAccent]
 *    via the alpha010 [tween] color helper.
 * 5. The knob position is the same animated `progress` mapped through
 *    `toRemoteDp()` and applied as `Modifier.offset` — avoids the
 *    weight()-on-derived-RemoteFloat issue that bit alpha08
 *    (b/504893436).
 *
 * @param tapAction Emitted as a `HostAction` so the runtime can call
 *   HA's service. The optimistic in-document flip happens regardless;
 *   the host is responsible for rolling state back if the service call
 *   fails.
 */
@Composable
@RemoteComposable
fun RemoteHaToggleSwitch(
    initiallyOn: Boolean,
    activeAccent: RemoteColor,
    inactiveAccent: RemoteColor,
    modifier: RemoteModifier = RemoteModifier,
    tapAction: HaAction = HaAction.None,
) {
    val localIsOn = rememberMutableRemoteBoolean(initiallyOn)
    val target: RemoteFloat = localIsOn.select(1f.rf, 0f.rf)
    val progress: RemoteFloat = animateRemoteFloat(target, ToggleDurationSeconds)

    val toggle: Action = ValueChange(localIsOn, localIsOn.not())
    val host: Action? = tapAction.toRemoteAction()
    val click: Action = if (host != null) CombinedAction(toggle, host) else toggle

    RemoteHaToggleSwitchByProgress(
        progress = progress,
        activeAccent = activeAccent,
        inactiveAccent = inactiveAccent,
        modifier = modifier,
        clickable = RemoteModifier.clickable(click),
    )
}

/**
 * Progress-driven variant — deterministic layout for any
 * [RemoteFloat] `progress` in `[0, 1]`. Exposed so previews can freeze
 * the switch at 0 / 0.5 / 1, and so callers (driving an externally
 * animated source like a host-bound RemoteBoolean) can supply their
 * own progress directly.
 *
 * Track color: `tween(inactiveAccent, activeAccent, progress)`.
 * Knob position: derived `start` padding —
 * `(KnobInsetDp + progress * KnobTravelDp).asRemoteDp()`. Use
 * `asRemoteDp` (the documented public converter — see
 * [RemoteCompose docs][rc]) and not the `toRemoteDp()` cousin: the
 * latter divides the input by display density (it expects px input and
 * returns dp), so feeding it a dp-scaled float silently scales the
 * travel down by `1 / density` (~38 % at 2.625) and the knob barely
 * moves. `asRemoteDp` wraps the float as-dp directly.
 *
 * `Modifier.offset(x: RemoteDp)` is not used here — see commit
 * f1832a9: `offset` with a RemoteFloat-derived RemoteDp visibly
 * collapsed the travel even when scaling was correct, so the position
 * is folded into start padding instead.
 *
 * [rc]: https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/remote/remote-creation-compose/src/main/java/androidx/compose/remote/creation/compose/state/RemoteDp.kt
 */
@Composable
@RemoteComposable
fun RemoteHaToggleSwitchByProgress(
    progress: RemoteFloat,
    activeAccent: RemoteColor,
    inactiveAccent: RemoteColor,
    modifier: RemoteModifier = RemoteModifier,
    clickable: RemoteModifier = RemoteModifier,
) {
    val trackColor: RemoteColor = tween(inactiveAccent, activeAccent, progress)
    val startPaddingDp =
        (KnobInsetDp.rf + progress * KnobTravelDp.rf).asRemoteDp()

    RemoteBox(
        modifier = modifier
            .size(width = TrackWidth, height = TrackHeight)
            .clip(RemoteRoundedCornerShape(11.rdp))
            .background(trackColor)
            .then(clickable),
    ) {
        RemoteBox(
            modifier = RemoteModifier
                .padding(start = startPaddingDp, top = KnobInset)
                .size(KnobSize)
                .clip(RemoteCircleShape)
                .background(Color.White.rc),
        )
    }
}
