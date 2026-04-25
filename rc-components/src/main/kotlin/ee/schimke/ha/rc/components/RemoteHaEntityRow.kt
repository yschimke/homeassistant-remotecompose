@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

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
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.lerp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rsp
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
    val clickable = data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val accent: RemoteColor = data.accent.isOn?.select(data.accent.activeAccent, data.accent.inactiveAccent)
        ?: data.accent.activeAccent

    RemoteRow(
        modifier = modifier.then(clickable).fillMaxWidth().padding(vertical = 6.rdp),
        horizontalArrangement = RemoteArrangement.SpaceBetween,
        verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
            RemoteIcon(
                imageVector = data.icon,
                contentDescription = data.name,
                modifier = RemoteModifier.size(20.rdp),
                tint = accent,
            )
            RemoteBox(modifier = RemoteModifier.padding(start = 12.rdp)) {
                RemoteText(
                    text = data.name,
                    color = theme.primaryText.rc,
                    fontSize = 13.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (data.accent.isOn != null) {
            RemoteHaToggleSwitch(
                initiallyOn = data.accent.initiallyOn,
                activeAccent = data.accent.activeAccent,
                inactiveAccent = data.accent.inactiveAccent,
                tapAction = data.tapAction,
            )
        } else {
            RemoteText(
                text = data.state,
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

/**
 * Pill-shaped toggle switch.
 *
 * STATIC for now — the visual reflects [initiallyOn] at document-build
 * time. The click only emits the host action; it does NOT flip the
 * visual in-document. To re-render after a service call, the host has
 * to re-encode the document.
 *
 * Why this is a downgrade from the androidx-main `ClickableDemo`
 * pattern: see [docs/bugs/rc-alpha08-select-derived-float-layout.md]
 * (filed as b/504893436). Briefly, in alpha08 a `RemoteFloat` derived from
 * `RemoteBoolean.select(1f.rf, 0f.rf)` (or any non-constant source like
 * `animateRemoteFloat`) cannot be consumed by `RemoteRowScope.weight`
 * or by `lerp(...)` inside a `Modifier.background` without breaking the
 * surrounding layout — the rounded clip is dropped and the inner
 * content disappears. Until that's fixed, the boolean has to live on
 * the host side; the document only ever sees a literal `0f.rf` / `1f.rf`.
 *
 * Once the bug is fixed, restore: `rememberMutableRemoteBoolean` +
 * `ValueChange(localIsOn, localIsOn.not())` + `animateRemoteFloat`
 * driving `RemoteHaToggleSwitchByProgress`.
 *
 * @param tapAction Emitted as a `HostAction` so the runtime can call
 *   HA's service. The host is responsible for re-encoding the document
 *   on the next state push.
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
    val progress: RemoteFloat = if (initiallyOn) 1f.rf else 0f.rf
    val host = tapAction.toRemoteAction()
    val click: RemoteModifier =
        if (host != null) RemoteModifier.clickable(host) else RemoteModifier

    RemoteHaToggleSwitchByProgress(
        progress = progress,
        activeAccent = activeAccent,
        inactiveAccent = inactiveAccent,
        modifier = modifier,
        clickable = click,
    )
}

/**
 * Progress-driven variant — deterministic layout for any
 * [RemoteFloat] `progress` in `[0, 1]`. Exposed so previews can
 * freeze the switch at 0 / 0.5 / 1 and verify the knob position.
 *
 * Layout: a `RemoteRow` with two weighted spacers around the knob.
 * left.weight = progress, right.weight = 1 − progress. Avoids
 * needing a dynamic `RemoteDp(RemoteFloat)` (that constructor is
 * internal in alpha08).
 *
 * Track color tweens per-channel between [inactiveAccent] and
 * [activeAccent] via `lerp`.
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
    val trackColor = lerpRemoteColor(inactiveAccent, activeAccent, progress)
    val rightWeight: RemoteFloat = 1f.rf - progress

    // Outer Box pins the size — inside an entity row, a Row with weighted
    // children would otherwise claim all available horizontal space, since
    // alpha08's `.size()` on a Row doesn't override the weight-driven
    // fillMax behavior. Box gives a hard size; the inner Row fills that.
    // `clickable` is composed last so it doesn't shadow the visual
    // modifiers (size/clip/background) in alpha08's modifier chain.
    RemoteBox(
        modifier = modifier
            .size(width = TrackWidth, height = TrackHeight)
            .clip(RemoteRoundedCornerShape(11.rdp))
            .background(trackColor)
            .then(clickable),
    ) {
        RemoteRow(
            modifier = RemoteModifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 2.rdp, vertical = 2.rdp),
            verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
            RemoteBox(modifier = RemoteModifier.weight(progress).fillMaxHeight())
            RemoteBox(
                modifier = RemoteModifier
                    .size(KnobSize)
                    .clip(RemoteCircleShape)
                    .background(Color.White.rc),
            )
            RemoteBox(modifier = RemoteModifier.weight(rightWeight).fillMaxHeight())
        }
    }
}

/**
 * Per-channel linear interpolation between two [RemoteColor]s. Pure
 * RemoteFloat math so the player can tween at playback time.
 */
private fun lerpRemoteColor(from: RemoteColor, to: RemoteColor, t: RemoteFloat): RemoteColor {
    val a = lerp(from.alpha, to.alpha, t)
    val r = lerp(from.red, to.red, t)
    val g = lerp(from.green, to.green, t)
    val b = lerp(from.blue, to.blue, t)
    return RemoteColor(a, r, g, b)
}
