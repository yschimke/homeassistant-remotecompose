@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `button` card — tappable card with a centered tinted icon chip and
 * name. Maps to `home-assistant/frontend` `hui-button-card.ts`.
 *
 * This file owns the two render tiers the Fixed-mode ladder in
 * `ButtonCardConverter` switches between (see
 * `docs/architecture/adaptive-card-layouts.md` §button):
 *
 *  * **icon + name** — [RemoteHaButton] / [RemoteHaToggleButton]. The
 *    shared [ButtonBody] self-centres (`fillMaxSize` + centre with a
 *    wrap-content column) so on cells taller than its intrinsic content
 *    it sits centred rather than pinned to the top edge (Principle 8),
 *    and a compact chip + reserved label line keeps the name from
 *    clipping on short cells (the `2×1` / Wear L clip this issue fixes).
 *  * **icon only** — [RemoteHaButtonIcon]. The tinted chip *is* the
 *    button, so the smallest cells (`1×1` launcher, Wear S) keep the
 *    icon instead of dropping to a text label (Principle 1 — never hide
 *    the icon first). The launcher already shows the name underneath the
 *    widget, so no in-cell text is needed.
 */
@Composable
@RemoteComposable
fun RemoteHaButton(
    data: HaButtonData,
    modifier: RemoteModifier = RemoteModifier,
    compact: Boolean = false,
) {
    // Static accent: read-only buttons stay on the active accent; a
    // toggleable entity selects active/inactive off its live binding
    // with no crossfade (the animated variant is [RemoteHaToggleButton]).
    val accent = data.staticAccent()
    ButtonBody(data, accent, modifier, compact)
}

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
fun RemoteHaToggleButton(
    data: HaButtonData,
    modifier: RemoteModifier = RemoteModifier,
    compact: Boolean = false,
) {
    // Live host binding for the entity's on/off state. Seeded from the
    // snapshot; the host pushes updates by name (`<entityId>.is_on`).
    val isOnBinding =
        if (data.accent.toggleable) LiveValues.isOn(data.entityId, data.accent.initiallyOn) else null

    // Tween the accent between inactive ↔ active so a host writeback
    // crossfades the icon halo and tint instead of snapping. Falls back
    // to the active accent unconditionally when there's no live binding
    // (preview / non-toggleable / null entityId).
    val accent: RemoteColor = if (isOnBinding != null) {
        val accentProgress = animateRemoteFloat(
            isOnBinding.select(1f.rf, 0f.rf),
            durationSeconds = 0.20f,
        )
        tween(data.accent.inactiveAccent, data.accent.activeAccent, accentProgress)
    } else {
        data.accent.activeAccent
    }

    ButtonBody(data, accent, modifier, compact)
}

/**
 * Icon-only identity tier — the tinted chip centred in the cell, no
 * name. Used by [ee.schimke.ha.rc.cards.ButtonCardConverter] on cells
 * too small to fit the label without clipping (`1×1` launcher, Wear S).
 * Tinted by the live on/off state for toggleable entities so the chip
 * still reads which state the button is in.
 */
@Composable
@RemoteComposable
fun RemoteHaButtonIcon(data: HaButtonData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val accent = data.staticAccent()
    val clickable =
        data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteBox(
        modifier = modifier
            .then(clickable)
            .then(cardChrome(theme.cardBackground, theme.divider)),
        contentAlignment = RemoteAlignment.Center,
    ) {
        ButtonIconChip(data.icon, data.name.rs, accent, FullChipDp, FullIconDp)
    }
}

/**
 * Read-only / un-animated accent: select active ↔ inactive off the live
 * binding for a toggleable entity, else the active accent. Shared by the
 * plain full button and the icon-only tier.
 */
@Composable
private fun HaButtonData.staticAccent(): RemoteColor {
    val isOnBinding =
        if (accent.toggleable) LiveValues.isOn(entityId, accent.initiallyOn) else null
    return isOnBinding?.select(accent.activeAccent, accent.inactiveAccent) ?: accent.activeAccent
}

/**
 * Shared icon + name body. The outer box applies [modifier] and centres
 * its content: pass `RemoteModifier.fillMaxSize()` (Fixed mode) so the
 * column self-centres in a cell larger than its intrinsic size instead
 * of pinning top-left, or the default wrap modifier (Wrap mode) so the
 * card hugs its content.
 *
 * [compact] shrinks the chip + paddings so the icon + reserved label
 * line fit a short launcher / Wear cell (down to `~84` dp tall) without
 * the name clipping — the Fixed-mode `icon+name` tier passes `true`. The
 * default (`false`) keeps the roomier wrap-mode dashboard sizing.
 */
@Composable
@RemoteComposable
private fun ButtonBody(
    data: HaButtonData,
    accent: RemoteColor,
    modifier: RemoteModifier,
    compact: Boolean,
) {
    val theme = haTheme()
    val clickable =
        data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val chipDp = if (compact) CompactChipDp else FullChipDp
    val iconDp = if (compact) CompactIconDp else FullIconDp
    val verticalPadDp = if (compact) 8 else 12
    val namePadDp = if (compact) 4 else 6
    // Not using `fillMaxWidth()` by default: buttons placed in a grid or
    // horizontal-stack should wrap-content so the flow layout can pack
    // multiple across the row. Standalone / Fixed-mode callers pass an
    // explicit `fillMaxSize()` (or `fillMaxWidth()`) modifier.
    RemoteBox(
        modifier = modifier
            .then(clickable)
            .then(cardChrome(theme.cardBackground, theme.divider))
            .padding(horizontal = 12.rdp, vertical = verticalPadDp.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
            ButtonIconChip(data.icon, data.name.rs, accent, chipDp, iconDp)
            if (data.showName) {
                RemoteBox(modifier = RemoteModifier.padding(top = namePadDp.rdp)) {
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

/** Tinted circular icon chip shared by both button tiers. */
@Composable
@RemoteComposable
private fun ButtonIconChip(
    icon: ImageVector,
    contentDescription: RemoteString,
    accent: RemoteColor,
    chipDp: Int,
    iconDp: Int,
) {
    RemoteBox(
        modifier = RemoteModifier
            .size(chipDp.rdp)
            .clip(RemoteCircleShape)
            .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteIcon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = RemoteModifier.size(iconDp.rdp),
            tint = accent,
        )
    }
}

private const val FullChipDp = 48
private const val FullIconDp = 28
private const val CompactChipDp = 44
private const val CompactIconDp = 26
