@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteImage
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * HA `picture-entity` card — modern image-with-overlay tile (`hui-picture-entity-card.ts`).
 *
 * ```
 *   ┌──────────────────────────────────┐
 *   │                                  │
 *   │              [icon]              │   ← image stub area
 *   │                                  │
 *   │  Name                  State     │   ← bottom translucent bar
 *   └──────────────────────────────────┘
 * ```
 *
 * RemoteCompose alpha08 doesn't accept arbitrary HTTP image references at capture time, so the
 * image area renders a flat tinted placeholder with a domain-appropriate icon. The bottom bar
 * matches HA's standard `hui-image-overlay`.
 *
 * The image is the card's identity at every size: it **fills and crops** to the cell
 * ([ContentScale.Crop]) so it never letterboxes, with the name/state strip kept as a translucent
 * scrim along the bottom. In [fillHeight] mode (launcher / Wear Fixed cells) the card grows to the
 * full cell height — so the picture earns the whole canvas rather than letterboxing into a band
 * (see docs/architecture/adaptive-card-layouts.md §"Picture family").
 *
 * Otherwise (the dashboard, Wrap) the card's height follows its live width at [aspectRatio] (width
 * ÷ height, HA's `aspect_ratio`), so it keeps its shape in a full-width slot and in a grid cell
 * alike. A null [aspectRatio] keeps the legacy fixed 160 dp band.
 */
@Composable
@RemoteComposable
fun RemoteHaPictureEntity(
  data: HaPictureEntityData,
  modifier: RemoteModifier = RemoteModifier,
  fillHeight: Boolean = false,
  aspectRatio: Float? = null,
) {
  if (!fillHeight && aspectRatio != null) {
    RemoteAspectRatioBox(aspectRatio, modifier) {
      RemoteHaPictureEntity(data, RemoteModifier.fillMaxSize(), fillHeight = true)
    }
    return
  }
  val theme = haTheme()
  val isOnBinding =
    if (data.accent.toggleable) LiveValues.isOn(data.entityId, data.accent.initiallyOn) else null
  // Only for an on/off glyph pair that differs while unavailable: no other part of this card reads
  // availability, and widgets (Fixed mode) carry no availability binding at all.
  val isAvailable =
    pairAvailability(
      data.entityId,
      data.accent.activeIconWhenUnavailable,
      data.accent.initiallyAvailable,
    )
  val accent =
    isOnBinding?.select(data.accent.themed().activeAccent, data.accent.themed().inactiveAccent)
      ?: data.accent.themed().activeAccent
  val clickable =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  val showStrip = data.showName || data.showState

  // Fill: the whole cell (Fixed) or the aspect box (Wrap with a ratio), so the
  // image is the identity edge-to-edge. Otherwise the legacy fixed band.
  val sizeModifier =
    if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth().height(160.rdp)

  RemoteBox(
    modifier =
      modifier.then(clickable).then(sizeModifier).then(cardChrome(theme.divider, theme.divider))
  ) {
    RemoteColumn(modifier = RemoteModifier.fillMaxSize()) {
      RemoteBox(
        modifier =
          RemoteModifier.fillMaxWidth()
            .weight(1f)
            .background(accent.copy(alpha = accent.alpha * 0.10f.rf)),
        contentAlignment = RemoteAlignment.Center,
      ) {
        if (data.imageUrl != null) {
          // The encoding form is picked by the embedding
          // surface via [LocalPictureImageStrategy]:
          //   - App dashboard → [PictureImageStrategy.Url],
          //     the player fetches via the configured
          //     `BitmapLoader` at playback.
          //   - Widgets → [PictureImageStrategy.Inline] with
          //     pre-fetched bytes baked into the doc; widget
          //     runtime has no loader.
          // Both go through the local
          // [rememberLocalNamedRemoteBitmap] helpers, which
          // work around the alpha010 1×1 default in upstream
          // `addNamedBitmapUrl(name, url)`. See
          // [LocalNamedRemoteBitmap.kt] and #277.
          val bindingName = data.entityId?.let(::pictureBindingName) ?: data.imageUrl
          val rb =
            when (val strategy = LocalPictureImageStrategy.current) {
              is PictureImageStrategy.Url ->
                rememberLocalNamedRemoteBitmap(
                  name = bindingName,
                  url = data.imageUrl,
                  width = strategy.widthPx,
                  height = strategy.heightPx,
                )
              is PictureImageStrategy.Inline ->
                strategy.bitmapFor(data.imageUrl)?.let { bytes ->
                  rememberLocalNamedRemoteBitmap(name = bindingName) { bytes }
                }
            }
          if (rb != null) {
            RemoteImage(
              remoteBitmap = rb,
              contentDescription = entityName(data.entityId, data.name),
              modifier = RemoteModifier.fillMaxSize(),
              // Fill-crop, not letterbox: the image owns the
              // whole cell at any aspect (HA's `cover`).
              contentScale = ContentScale.Crop,
            )
          } else {
            // Inline strategy with no pre-fetched bytes for
            // this URL — fall back to the icon path so the
            // tile renders something instead of a hole.
            HaStateIcon(
              icon = data.icon,
              activeIcon = data.accent.activeIcon,
              initiallyOn = data.accent.initiallyOn,
              isOn = isOnBinding,
              activeWhenUnavailable = data.accent.activeIconWhenUnavailable,
              initiallyAvailable = data.accent.initiallyAvailable,
              isAvailable = isAvailable,
              tint = accent.copy(alpha = accent.alpha * 0.55f.rf),
              contentDescription = entityName(data.entityId, data.name),
              modifier = RemoteModifier.size(40.rdp),
            )
          }
        } else {
          HaStateIcon(
            icon = data.icon,
            activeIcon = data.accent.activeIcon,
            initiallyOn = data.accent.initiallyOn,
            isOn = isOnBinding,
            activeWhenUnavailable = data.accent.activeIconWhenUnavailable,
            initiallyAvailable = data.accent.initiallyAvailable,
            isAvailable = isAvailable,
            tint = accent.copy(alpha = accent.alpha * 0.55f.rf),
            contentDescription = entityName(data.entityId, data.name),
            modifier = RemoteModifier.size(40.rdp),
          )
        }
        if (data.frameStamp != null) {
          RemoteBox(
            modifier =
              RemoteModifier.padding(8.rdp)
                .background(theme.cardBackground.copy(alpha = theme.cardBackground.alpha * 0.8f.rf))
                .padding(horizontal = 6.rdp, vertical = 2.rdp)
          ) {
            RemoteText(
              text = data.frameStamp.rs,
              color = theme.secondaryText,
              fontSize = HaType.Micro,
              style = haTextStyle(),
              maxLines = 1,
            )
          }
        }
      }
      if (showStrip) {
        val barBg = theme.cardBackground
        RemoteRow(
          modifier =
            RemoteModifier.fillMaxWidth()
              .background(barBg.copy(alpha = barBg.alpha * 0.85f.rf))
              .padding(horizontal = 12.rdp, vertical = 8.rdp),
          verticalAlignment = RemoteAlignment.CenterVertically,
          horizontalArrangement = RemoteArrangement.SpaceBetween,
        ) {
          RemoteText(
            text = if (data.showName) entityName(data.entityId, data.name) else "".rs,
            color = theme.primaryText,
            fontSize = HaType.Body,
            fontWeight = FontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (data.showState) {
            RemoteText(
              text = data.state,
              color = theme.secondaryText,
              fontSize = HaType.Label,
              style = haTextStyle(),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}
