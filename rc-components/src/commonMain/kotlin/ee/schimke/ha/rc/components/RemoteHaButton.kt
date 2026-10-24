@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * HA `button` card — tappable card with a centered tinted icon chip and name. Maps to
 * `home-assistant/frontend` `hui-button-card.ts`.
 *
 * This file owns the two render tiers the Fixed-mode ladder in `ButtonCardConverter` switches
 * between (see `docs/architecture/adaptive-card-layouts.md` §button):
 *
 * * **icon + name** — [RemoteHaButton] / [RemoteHaToggleButton]. The shared [ButtonBody]
 *   self-centres (`fillMaxSize` + centre with a wrap-content column) so on cells taller than its
 *   intrinsic content it sits centred rather than pinned to the top edge (Principle 8), and a
 *   compact chip + reserved label line keeps the name from clipping on short cells (the `2×1` /
 *   Wear L clip this issue fixes).
 * * **icon beside name** — [RemoteHaButtonRow], for a canvas too short for the name under the icon
 *   but wide enough beside it (the Wear Small container): the name is what tells two buttons apart
 *   (card-information-hierarchy.md).
 * * **icon chip** — [RemoteHaButtonIcon]. The tinted chip *is* the button, so the smallest cells
 *   (`1×1` launcher) keep the icon instead of dropping to a text label (Principle 1 — never hide
 *   the icon first), with the name under it unless the icon is evocative.
 */
@Composable
@RemoteComposable
fun RemoteHaButton(
  data: HaButtonData,
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
  dense: Boolean = false,
) {
  // Static accent: read-only buttons stay on the active accent; a
  // toggleable entity selects active/inactive off its live binding
  // with no crossfade (the animated variant is [RemoteHaToggleButton]).
  val look = entityLook(data.entityId, data.accent)
  ButtonBody(data, look.accent, look, modifier, compact, dense)
}

/**
 * A toggleable variant of [RemoteHaButton].
 *
 * The accent color tracks the entity's live `<entityId>.is_on` binding (built from
 * [HaButtonData.accent.initiallyOn] as the authoring-time seed). On click we emit the configured
 * [HaAction] — usually a [HaAction.Toggle] [HostAction] — and the host writes the new value back to
 * the same binding. There is no in-document optimistic flip; see `RemoteHaToggleSwitch` for the
 * same model.
 *
 * For non-toggleable entities pass [HaToggleAccent.toggleable] = false (or use the plain
 * [RemoteHaButton]); without a live boolean the accent stays on [activeAccent] unconditionally.
 */
@Composable
@RemoteComposable
fun RemoteHaToggleButton(
  data: HaButtonData,
  modifier: RemoteModifier = RemoteModifier,
  compact: Boolean = false,
  dense: Boolean = false,
) {
  // Live host binding for the entity's on/off state. Seeded from the
  // snapshot; the host pushes updates by name (`<entityId>.is_on`).
  val look = entityLook(data.entityId, data.accent)
  ButtonBody(data, toggleAccent(data, look), look, modifier, compact, dense)
}

/**
 * The toggle button's accent: tweened between inactive ↔ active so a host writeback crossfades the
 * icon halo and tint instead of snapping. Falls back to the active accent unconditionally when
 * there's no live binding (preview / non-toggleable / null entityId). An unavailable entity takes
 * the look's neutral accent instead.
 */
@Composable
private fun toggleAccent(data: HaButtonData, look: HaEntityLook): RemoteColor {
  val isOnBinding = look.isOn ?: return look.accent
  val accentProgress = animateRemoteFloat(isOnBinding.select(1f.rf, 0f.rf), durationSeconds = 0.20f)
  return look.available(
    tween(data.accent.themed().inactiveAccent, data.accent.themed().activeAccent, accentProgress)
  )
}

/**
 * Icon-beside-name tier — the tinted chip, then the name and (with `show_state`) the state, on one
 * line. For a canvas too short for the name under the icon but wide enough beside it: the Wear
 * Small container (a ~44dp content box), where the icon alone would leave two buttons on one watch
 * looking the same. The name ellipsises before the state does; the accent follows the live
 * `<entityId>.is_on` binding (tweened for a toggleable entity, as [RemoteHaToggleButton]) and the
 * state is the host's `<entityId>.state` binding, so the row keeps up without a re-encode.
 */
@Composable
@RemoteComposable
fun RemoteHaButtonRow(data: HaButtonData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val look = entityLook(data.entityId, data.accent)
  val accent = if (data.accent.toggleable) toggleAccent(data, look) else look.accent
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteBox(
    modifier =
      modifier
        .then(clickable)
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 3.rdp),
    contentAlignment = RemoteAlignment.CenterStart,
  ) {
    RemoteRow(
      modifier = RemoteModifier.fillMaxWidth(),
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      if (data.showIcon) ButtonIconChip(data, look, accent, RowChipDp, RowIconDp)
      RemoteBox(
        modifier =
          RemoteModifier.weight(1f).padding(start = (if (data.showIcon) 10 else 0).rdp, end = 8.rdp)
      ) {
        RemoteText(
          text = entityName(data.entityId, data.name),
          color = look.dim(theme.primaryText),
          fontSize = HaType.Body,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      data.state?.let { state ->
        RemoteText(
          text = state,
          color = look.dim(theme.primaryText),
          fontSize = HaType.Body,
          style = haTextStyle(),
          maxLines = 1,
        )
      }
    }
  }
}

/**
 * Identity tier — the tinted chip centred in the cell. Used by
 * [ee.schimke.ha.rc.cards.ButtonCardConverter] on cells too small for the full button's name line
 * (`1×1` launcher). Tinted by the live on/off state for toggleable entities so the chip still reads
 * which state the button is in.
 *
 * [showName] keeps the name under the chip, at [HaType.Label] and ellipsised, for a generic icon (a
 * bulb, a switch) that would leave two such widgets alike; the chip shrinks to the compact size to
 * make room. Without it the chip stands alone, for an evocative icon or a configured `icon:`
 * (card-information-hierarchy.md, the icon exception).
 */
@Composable
@RemoteComposable
fun RemoteHaButtonIcon(
  data: HaButtonData,
  modifier: RemoteModifier = RemoteModifier,
  showName: Boolean = false,
) {
  val theme = haTheme()
  val look = entityLook(data.entityId, data.accent)
  val accent = look.accent
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteBox(
    modifier =
      modifier
        .then(clickable)
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = (if (showName) 6 else 0).rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    if (!showName) ButtonIconChip(data, look, accent, FullChipDp, FullIconDp, iconOnly = true)
    // 44dp chip + 4dp + a Label line (~17dp) is ~65dp: inside a 1×1 cell's 84dp.
    else
      RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
        ButtonIconChip(data, look, accent, CompactChipDp, CompactIconDp)
        RemoteBox(modifier = RemoteModifier.padding(top = 4.rdp)) {
          RemoteText(
            text = entityName(data.entityId, data.name),
            color = look.dim(theme.primaryText),
            fontSize = HaType.Label,
            fontWeight = FontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
  }
}

/**
 * Shared icon + name body. The outer box applies [modifier] and centres its content: pass
 * `RemoteModifier.fillMaxSize()` (Fixed mode) so the column self-centres in a cell larger than its
 * intrinsic size instead of pinning top-left, or the default wrap modifier (Wrap mode) so the card
 * hugs its content.
 *
 * [compact] shrinks the chip + paddings so the icon + reserved label line fit a short launcher /
 * Wear cell (down to `~84` dp tall) without the name clipping — the Fixed-mode `icon+name` tier
 * passes `true`. The default (`false`) keeps the roomier wrap-mode dashboard sizing.
 *
 * [dense] is the `show_state` form for a cell too short for the compact one with its state line (a
 * `1×1` or `2×1` launcher widget, ~84 dp): the row-sized chip, the name and the state at label size
 * and tighter gaps (4 + 36 + 2 + 17 + 17 + 4 ≈ 80 dp), so the state, which is the button's value,
 * stays whole instead of being cut at the bottom (card-information-hierarchy.md).
 */
@Composable
@RemoteComposable
private fun ButtonBody(
  data: HaButtonData,
  accent: RemoteColor,
  look: HaEntityLook,
  modifier: RemoteModifier,
  compact: Boolean,
  dense: Boolean = false,
) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val chipDp =
    when {
      dense -> RowChipDp
      compact -> CompactChipDp
      else -> FullChipDp
    }
  val iconDp =
    when {
      dense -> RowIconDp
      compact -> CompactIconDp
      else -> FullIconDp
    }
  // The dashboard tier keeps HA's 187×91 reference canvas: 8dp + the 48dp chip + 6dp gap + a
  // Body (15sp, ~20dp) line + 8dp is ~90dp. HA web's 12dp padding fit its 13px name, not this one.
  val verticalPadDp =
    when {
      dense -> 4
      compact -> 6
      else -> 8
    }
  val namePadDp =
    when {
      dense -> 2
      compact -> 4
      else -> 6
    }
  // Widget (compact) tier gives the name a touch more presence than
  // the dashboard's Body — a single button has no extra data to fill a
  // big cell with, so a bolder label is the cheapest way to use the
  // space. BodyLarge (16sp, ~19dp line) + the 44dp chip + 4dp gap + 6dp
  // vertical padding is ~79dp, which clears the shortest icon+name cell
  // (`2×1`, 84dp) and the converter's 80dp icon+name gate.
  val nameSize =
    when {
      dense -> HaType.Label
      compact -> HaType.BodyLarge
      else -> HaType.Body
    }
  // Not using `fillMaxWidth()` by default: buttons placed in a grid or
  // horizontal-stack should wrap-content so the flow layout can pack
  // multiple across the row. Standalone / Fixed-mode callers pass an
  // explicit `fillMaxSize()` (or `fillMaxWidth()`) modifier.
  RemoteBox(
    modifier =
      modifier
        .then(clickable)
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = (if (dense) 6 else 12).rdp, vertical = verticalPadDp.rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
      // HA's `show_icon: false` drops the icon; the name (and state) then lead the column.
      if (data.showIcon) ButtonIconChip(data, look, accent, chipDp, iconDp)
      if (data.showName) {
        RemoteBox(
          modifier = RemoteModifier.padding(top = (if (data.showIcon) namePadDp else 0).rdp)
        ) {
          RemoteText(
            text = entityName(data.entityId, data.name),
            color = look.dim(theme.primaryText),
            fontSize = nameSize,
            fontWeight = FontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      // HA's `show_state: true` line under the name: the live `<id>.state` binding, so it follows
      // the entity without a re-encode.
      data.state?.let { state ->
        RemoteText(
          text = state,
          color = look.dim(theme.secondaryText),
          fontSize = HaType.Label,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** Tinted circular icon chip shared by both button tiers. */
@Composable
@RemoteComposable
private fun ButtonIconChip(
  data: HaButtonData,
  look: HaEntityLook,
  accent: RemoteColor,
  chipDp: Int,
  iconDp: Int,
  iconOnly: Boolean = false,
) {
  val console = visualFamily() == HaVisualFamily.Console
  RemoteBox(
    modifier =
      RemoteModifier.size(chipDp.rdp)
        .clip(haControlShape(20, round = true))
        .then(
          if (console) RemoteModifier
          else if (visualFamily() == HaVisualFamily.Ikea) ikeaKeyChrome(accent)
          else RemoteModifier.background(accent.copy(alpha = accent.alpha * 0.2f.rf))
        ),
    contentAlignment = RemoteAlignment.Center,
  ) {
    if (console)
      ConsoleControlFace(
        look.isOn?.select(accent, ConsoleSlot.rc) ?: accent,
        RemoteModifier.size(chipDp.rdp),
        look.isOn?.select(1f.rf, 0f.rf),
      )
    HaStateIcon(
      icon = data.icon,
      accent = data.accent,
      look = look,
      tint = if (console) look.dim(haTheme().primaryText) else accent,
      // Decorative when the name is drawn under it; the icon alone carries it otherwise.
      contentDescription =
        if (data.showName && !iconOnly) null else entityName(data.entityId, data.name),
      modifier =
        RemoteModifier.then(
            if (console) RemoteModifier.padding(end = (chipDp * 0.28f).rdp) else RemoteModifier
          )
          .size((if (console) iconDp - 6 else iconDp).rdp),
    )
  }
}

private const val FullChipDp = 48
private const val FullIconDp = 28
private const val CompactChipDp = 44
private const val CompactIconDp = 26
private const val RowChipDp = 36
private const val RowIconDp = 22
