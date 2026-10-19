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
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

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
  val look = entityLook(data.entityId, data.accent)
  val accent: RemoteColor = look.accent
  // Toggleable entities always get the chip (color varies with isOn);
  // read-only entities (no isOn binding) render icon plain.
  val renderChip = look.isOn != null

  RemoteBox(
    modifier =
      modifier
        .then(clickable)
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        .padding(horizontal = 12.rdp, vertical = 8.rdp)
  ) {
    if (data.features.isEmpty()) {
      TileInfoRow(data, look, renderChip)
    } else {
      RemoteColumn(modifier = RemoteModifier.fillMaxWidth()) {
        TileInfoRow(data, look, renderChip)
        TileFeaturesBelow(data, accent)
      }
    }
  }
}

/**
 * The tile's icon, name and state — and, with [HaTileFeaturePosition.Inline], its first feature
 * beside them.
 */
@Composable
@RemoteComposable
private fun TileInfoRow(data: HaTileData, look: HaEntityLook, renderChip: Boolean) {
  val theme = haTheme()
  val accent: RemoteColor = look.accent
  val inline =
    data.features.firstOrNull()?.takeIf { data.featuresPosition == HaTileFeaturePosition.Inline }
  if (inline == null && data.badge == null) {
    val family = visualFamily()
    val identity: @Composable () -> Unit = {
      TileIdentity(data, look, renderChip) { TileIcon(data, look, renderChip) }
    }
    if (FamilyTileInfo(family, data, look, identity)) return
    if (family.isCommunity) {
      CommunityTileInfo(data, look, identity)
      return
    }
  }
  RemoteRow(
    modifier = if (inline != null) RemoteModifier.fillMaxWidth() else RemoteModifier,
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    val badge = data.badge
    if (badge != null) {
      TileIconWithBadge(badge, iconSlotDp = if (renderChip) 32 else 24) {
        TileIcon(data, look, renderChip)
      }
    } else {
      TileIdentity(data, look, renderChip) { TileIcon(data, look, renderChip) }
    }
    RemoteColumn(
      modifier =
        if (inline != null) RemoteModifier.weight(1f).padding(start = 10.rdp)
        else RemoteModifier.padding(start = 10.rdp)
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
      // The state is what a tile is read for, so it is body-sized and in the primary ink; the
      // name keeps the emphasis through its weight. (A caption-sized grey state was the smallest,
      // lowest-contrast text on the card.)
      RemoteText(
        text = data.state,
        color = look.dim(theme.primaryText),
        fontSize = HaType.Body,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (inline != null) {
      RemoteBox(modifier = RemoteModifier.weight(1f).padding(start = TileFeatureGapDp.rdp)) {
        TileFeature(inline, accent, TileInlineFeatureHeightDp, data.name)
      }
    }
  }
}

/**
 * The tile's state icon: in a tinted chip for an entity with on/off chrome, else plain. An on/off
 * entity swaps between its two glyphs through [look]'s `is_on` ([HaStateIcon]).
 */
@Composable
@RemoteComposable
private fun TileIcon(data: HaTileData, look: HaEntityLook, renderChip: Boolean) {
  // Decorative: the tile draws the name beside it.
  val accent = look.accent
  if (renderChip && visualFamily() == HaVisualFamily.Ikea && look.isOn != null) {
    IkeaControlFace(accent, look.isOn.select(1f.rf, 0f.rf), RemoteModifier.size(32.rdp))
  } else if (renderChip && visualFamily() == HaVisualFamily.Console) {
    ConsoleControlFace(
      look.isOn?.select(accent, ConsoleSlot.rc) ?: accent,
      RemoteModifier.size(32.rdp),
      look.isOn?.select(1f.rf, 0f.rf),
    )
  } else if (renderChip) {
    RemoteBox(
      modifier =
        RemoteModifier.size(32.rdp)
          .clip(haControlShape(18, round = true))
          .background(accent.copy(alpha = accent.alpha * 0.2f.rf)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      HaStateIcon(
        icon = data.icon,
        accent = data.accent,
        look = look,
        tint = accent,
        contentDescription = null,
        modifier = RemoteModifier.size(20.rdp),
      )
    }
  } else if (visualFamily() == HaVisualFamily.Console && data.tapAction is HaAction.CallService) {
    RemoteBox(
      modifier = RemoteModifier.size(24.rdp).then(consoleKeyChrome(accent)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      HaStateIcon(
        icon = data.icon,
        accent = data.accent,
        look = look,
        tint = accent,
        contentDescription = null,
        modifier = RemoteModifier.size(18.rdp),
      )
    }
  } else {
    HaStateIcon(
      icon = data.icon,
      accent = data.accent,
      look = look,
      tint = accent,
      contentDescription = null,
      modifier = RemoteModifier.size(24.rdp),
    )
  }
}

/**
 * The tile for a short Fixed-mode canvas (the Wear small slot, whose content box is ~44dp): the
 * same icon, name and state, with the name a step down to [HaType.Label] and the padding tightened,
 * because the full tile's two body lines and padding need [TileTwoLineMinHeightDp]. The state keeps
 * its body size — it is what the tile is read for — and the name stays whole rather than being
 * ellipsised beside it on one line (design principle 5: compact is designed, not clipped).
 *
 * A state-layout sibling of [RemoteHaTile], so like [RemoteHaIconChip] it resolves the accent and
 * the unavailable dimming from the encode-time state rather than registering `<entity>.is_on` /
 * `is_available` again (a duplicate named `RemoteBoolean` blanks the other branch in
 * alpha010, #224); the state text stays live.
 */
@Composable
@RemoteComposable
fun RemoteHaTileCompact(data: HaTileData, modifier: RemoteModifier = RemoteModifier) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val look = entityLook(data.entityId, data.accent, live = false)
  val accent: RemoteColor = look.accent

  RemoteBox(
    modifier =
      modifier
        .then(clickable)
        .fillMaxWidth()
        .then(cardChrome(theme.cardBackground, theme.divider))
        // A Label line (~17dp) over a Body line (~20dp) inside 3dp: ~43dp of the slot's ~44.
        .padding(horizontal = 12.rdp, vertical = 3.rdp)
  ) {
    RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
      // A capture-selected launcher tier can bind state without colliding with hidden siblings.
      RemoteBox(
        modifier =
          RemoteModifier.size(24.rdp)
            .then(
              if (data.tapAction != HaAction.None) consoleKeyChrome(accent) else RemoteModifier
            ),
        contentAlignment = RemoteAlignment.Center,
      ) {
        HaStateIcon(
          icon = data.icon,
          accent = data.accent,
          look = look,
          tint = accent,
          // Decorative: the name is drawn beside it.
          contentDescription = null,
          modifier = RemoteModifier.size(24.rdp),
        )
      }
      RemoteColumn(modifier = RemoteModifier.padding(start = 10.rdp)) {
        RemoteText(
          text = entityName(data.entityId, data.name),
          color = look.dim(theme.primaryText),
          fontSize = HaType.Label,
          fontWeight = FontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteText(
          text = data.state,
          color = look.dim(theme.primaryText),
          fontSize = HaType.Body,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * The height [RemoteHaTile]'s name-over-state stack needs: two [HaType.Body] lines (~20dp each)
 * inside its 8dp vertical padding. A Fixed-mode canvas shorter than this takes
 * [RemoteHaTileCompact].
 */
const val TileTwoLineMinHeightDp: Int = 56

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
 *   accent, and the unavailable dimming, are resolved from the encode-time state instead.
 * * **No clipped circle backing.** A `.clip(RemoteCircleShape)` + `background` box in this branch
 *   likewise blanks the sibling full tier at playback. The tinted glyph alone carries the identity
 *   — the circle halo is the cosmetic part, and dropping it costs nothing semantically.
 *
 * [bindState] is safe when capture selected only this tier (launcher templates): it lets the bake
 * apply the real on/off tint instead of the placeholder entity's state.
 *
 * Both are cosmetic — the icon, not its tint animation or halo, is what says what kind of card this
 * is. Revisit the circle / live colour once the alpha010 state-layout bugs (#224) lift.
 */
@Composable
@RemoteComposable
fun RemoteHaIconChip(
  data: HaTileData,
  modifier: RemoteModifier = RemoteModifier,
  bindState: Boolean = false,
) {
  val theme = haTheme()
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val look = entityLook(data.entityId, data.accent, live = bindState)
  val accent: RemoteColor = look.accent

  RemoteColumn(
    modifier = modifier.then(clickable).fillMaxSize().padding(4.rdp),
    verticalArrangement = RemoteArrangement.Center,
    horizontalAlignment = RemoteAlignment.CenterHorizontally,
  ) {
    // A capture-selected launcher tier can bind state without colliding with hidden siblings.
    RemoteBox(
      modifier =
        RemoteModifier.size(32.rdp)
          .then(if (data.tapAction != HaAction.None) consoleKeyChrome(accent) else RemoteModifier),
      contentAlignment = RemoteAlignment.Center,
    ) {
      HaStateIcon(
        icon = data.icon,
        accent = data.accent,
        look = look,
        tint = accent,
        contentDescription = entityName(data.entityId, data.name),
        modifier = RemoteModifier.size(32.rdp),
      )
    }
    RemoteBox(modifier = RemoteModifier.padding(top = 6.rdp)) {
      // The chip's only text, so it reads as the value: primary ink, a step above a caption.
      RemoteText(
        text = data.state,
        color = look.dim(theme.primaryText),
        fontSize = HaType.Label,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun CommunityTileInfo(
  data: HaTileData,
  look: HaEntityLook,
  identity: @Composable () -> Unit,
) {
  val family = visualFamily()
  val theme = haTheme()
  val label: @Composable () -> Unit = {
    RemoteText(
      entityName(data.entityId, data.name),
      color = look.dim(theme.primaryText),
      fontSize = HaType.Body,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  val state: @Composable () -> Unit = {
    RemoteText(
      data.state,
      color = look.dim(theme.primaryText),
      fontSize = HaType.BodyLarge,
      fontWeight = haValueWeight(),
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    if (family != HaVisualFamily.Minimalist && family != HaVisualFamily.Arctic) identity()
    RemoteColumn(RemoteModifier.weight(1f).padding(horizontal = 10.rdp)) {
      if (family == HaVisualFamily.Cupertino || family == HaVisualFamily.Minimalist) {
        state()
        label()
      } else {
        label()
        if (family != HaVisualFamily.Bubble) state()
      }
    }
    if (family == HaVisualFamily.Bubble) state()
    if (family == HaVisualFamily.Minimalist || family == HaVisualFamily.Arctic) identity()
  }
}
