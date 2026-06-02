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
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.state.RemoteColor
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
 * HA `tile` card — modern primary-info card (`home-assistant/frontend` → `hui-tile-card.ts`).
 *
 * ```
 *   ┌──────────────────────────────────┐
 *   │  [●] Name                        │
 *   │      State                       │
 *   └──────────────────────────────────┘
 * ```
 */
@Composable
@RemoteComposable
fun RemoteHaTile(data: HaTileData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val isOnBinding =
    if (data.accent.toggleable) LiveValues.isOn(data.entityId, data.accent.initiallyOn) else null
  val accent: RemoteColor =
    isOnBinding?.select(data.accent.activeAccent, data.accent.inactiveAccent)
      ?: data.accent.activeAccent
  // Toggleable entities always get the chip (color varies with isOn);
  // read-only entities (no isOn binding) render icon plain.
  val renderChip = isOnBinding != null

  RemoteBox(
    modifier =
      modifier
        .then(clickable)
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 8.rdp)
  ) {
    RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
      if (renderChip) {
        RemoteBox(
          modifier =
            RemoteModifier.size(32.rdp)
              .clip(RemoteCircleShape)
              .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
          contentAlignment = RemoteAlignment.Center,
        ) {
          RemoteIcon(
            imageVector = data.icon,
            contentDescription = data.name.rs,
            modifier = RemoteModifier.size(20.rdp),
            tint = accent,
          )
        }
      } else {
        RemoteIcon(
          imageVector = data.icon,
          contentDescription = data.name.rs,
          modifier = RemoteModifier.size(24.rdp),
          tint = accent,
        )
      }
      RemoteColumn(modifier = RemoteModifier.padding(start = 10.rdp)) {
        RemoteText(
          text = data.name.rs,
          color = theme.primaryText.rc,
          fontSize = 13.rsp,
          fontWeight = FontWeight.Medium,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteText(
          text = data.state,
          color = theme.secondaryText.rc,
          fontSize = 11.rsp,
          style = RemoteTextStyle.Default,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * Icon-only identity tier for the icon-+-state family (`tile`, `entity`, `sensor`, `statistic`).
 * Centres the tinted state icon in the cell with a single state line beneath it — the "minimum
 * viable identity" rung (P1: icon + value; P2 name dropped) that Fixed-mode converters fall to on
 * the smallest launcher cells.
 *
 * This is the compact tier the family was missing: the previous `Full → CompactStateChip` ladder
 * hid the icon first and rendered a bare text chip (`21.4 °C`) on a `1×1` cell — a direct violation
 * of Principle 1 (_identity before density_) and the "hiding the icon first" anti-pattern. The chip
 * keeps the icon at every size; the text chip is demoted to the genuine last resort (only reached
 * when even a ~40 dp icon can't fit). It [fillMaxSize]s and self-centres so the cell is never
 * half-empty (Principle 8), the way [RemoteHaArcDialWide] does — a wrapper `contentAlignment` is a
 * no-op for `fillMaxWidth` children in alpha010.
 *
 * Reuses [HaTileData] so every member of the family can feed the same tier; the icon is tinted by
 * the entity's state colour.
 *
 * Two deliberate alpha010 concessions vs the full tile's icon treatment, both because this
 * composable is a **state-layout sibling** of the full tier and a hidden branch can corrupt the
 * visible one:
 *
 * * **Static accent, no live `isOn` `select`.** The full tier already registers `<entity>.is_on`;
 *   re-registering that named `RemoteBoolean` here collides and blanks the other branch (a
 *   duplicate named `RemoteString` like `state` is fine — a duplicate `RemoteBoolean` is not). The
 *   accent is resolved from the encode-time state instead.
 * * **No clipped circle backing.** A `.clip(RemoteCircleShape)` + `background` box in this branch
 *   likewise blanks the sibling full tier at playback. The tinted glyph alone carries the identity
 *   — the circle halo is the cosmetic part, and dropping it costs nothing semantically.
 *
 * Both are cosmetic — the icon, not its tint animation or halo, is what says what kind of card this
 * is. Revisit the circle / live colour once the alpha010 state-layout bugs (#224) lift.
 */
@Composable
@RemoteComposable
fun RemoteHaIconChip(data: HaTileData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val accent: RemoteColor =
    if (data.accent.toggleable && !data.accent.initiallyOn) data.accent.inactiveAccent
    else data.accent.activeAccent

  RemoteColumn(
    modifier = modifier.then(clickable).fillMaxSize().padding(4.rdp),
    verticalArrangement = RemoteArrangement.Center,
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    RemoteIcon(
      imageVector = data.icon,
      contentDescription = data.name.rs,
      modifier = RemoteModifier.size(32.rdp),
      tint = accent,
    )
    RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
      RemoteText(
        text = data.state,
        color = theme.secondaryText.rc,
        fontSize = 11.rsp,
        style = RemoteTextStyle.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
