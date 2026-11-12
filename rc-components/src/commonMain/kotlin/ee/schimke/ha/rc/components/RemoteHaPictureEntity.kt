@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteContentScale
import ee.schimke.composeai.rccreation.compose.layout.RemoteImage
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.RemoteImageBitmap
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow

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
 * ([RemoteContentScale.Crop]) so it never letterboxes, with the name/state strip kept as a
 * translucent scrim along the bottom. In [fillHeight] mode (launcher / Wear Fixed cells) the card
 * grows to the full cell height — so the picture earns the whole canvas rather than letterboxing
 * into a band (see docs/architecture/adaptive-card-layouts.md §"Picture family").
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
  // With the name hidden nothing inside the target says what it is: `RemoteImage` writes no
  // description for a loaded picture. The target carries the name then, and the glyph that stands
  // in for a missing picture stops repeating it.
  val action = data.tapAction.toRemoteAction()
  val targetNamed = action != null && !data.showName
  val name = entityName(data.entityId, data.name)
  val clickable =
    action?.let { RemoteModifier.haClickable(it, name.takeIf { targetNamed }) } ?: RemoteModifier
  val glyphName = name.takeUnless { targetNamed }
  val showStrip = data.showName || data.showState

  // Fill: the whole cell (Fixed) or the aspect box (Wrap with a ratio), so the
  // image is the identity edge-to-edge. Otherwise the legacy fixed band.
  val sizeModifier =
    if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth().height(160.rdp)

  RemoteBox(
    modifier =
      modifier.then(clickable).then(sizeModifier).then(cardChrome(theme.divider, theme.divider)),
  ) {
    RemoteColumn(modifier = RemoteModifier.fillMaxSize()) {
      // The picture's area: under a kit with display windows a camera's feed is in one, as its
      // readouts are; else the area tinted in the accent, edge to edge.
      val area = RemoteModifier.fillMaxWidth().weight(1f.rf)
      val picture: @Composable @RemoteComposable () -> Unit = {
        if (haLayouts().notes == HaNoteStyle.Tape)
          ConsoleTapeLabel(
            (if (data.imageUrl == null) "No image" else "Image unavailable").rs,
            RemoteModifier.padding(6.rdp),
          )
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
          val rb = rememberPictureBitmap(data.imageUrl, data.pictureEntityId)
          if (rb != null) {
            RemoteImage(
              bitmap = rb,
              contentDescription = glyphName,
              modifier = RemoteModifier.fillMaxSize(),
              // Fill-crop, not letterbox: the image owns the
              // whole cell at any aspect (HA's `cover`).
              contentScale = RemoteContentScale.Crop,
            )
          } else {
            // Inline strategy with no pre-fetched bytes for
            // this URL — fall back to the icon path so the
            // tile renders something instead of a hole.
            if (haLayouts().notes != HaNoteStyle.Tape)
              HaStateIcon(
                icon = data.icon,
                activeIcon = data.accent.activeIcon,
                initiallyOn = data.accent.initiallyOn,
                isOn = isOnBinding,
                activeWhenUnavailable = data.accent.activeIconWhenUnavailable,
                initiallyAvailable = data.accent.initiallyAvailable,
                isAvailable = isAvailable,
                tint = accent.copy(alpha = accent.alpha * 0.55f.rf),
                contentDescription = glyphName,
                modifier = RemoteModifier.size(40.rdp),
              )
          }
        } else {
          if (haLayouts().notes != HaNoteStyle.Tape)
            HaStateIcon(
              icon = data.icon,
              activeIcon = data.accent.activeIcon,
              initiallyOn = data.accent.initiallyOn,
              isOn = isOnBinding,
              activeWhenUnavailable = data.accent.activeIconWhenUnavailable,
              initiallyAvailable = data.accent.initiallyAvailable,
              isAvailable = isAvailable,
              tint = accent.copy(alpha = accent.alpha * 0.55f.rf),
              contentDescription = glyphName,
              modifier = RemoteModifier.size(40.rdp),
            )
        }
        if (data.frameStamp != null) {
          RemoteBox(
            modifier =
              RemoteModifier.padding(8.rdp)
                .background(theme.cardBackground.copy(alpha = theme.cardBackground.alpha * 0.8f.rf))
                .padding(horizontal = 6.rdp, vertical = 2.rdp),
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
      if (cameraInLcdWindow(data.pictureEntityId)) LcdPictureWindow(area, picture)
      else
        RemoteBox(
          modifier = area.background(accent.copy(alpha = accent.alpha * 0.10f.rf)),
          contentAlignment = RemoteAlignment.Center,
        ) {
          picture()
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
            text = if (data.showName) name else "".rs,
            // The name takes what the state leaves: unweighted, a narrow strip (a Wear Large
            // slot on a Galaxy watch) dropped it whole.
            modifier = RemoteModifier.weight(1f.rf),
            color = theme.primaryText,
            fontSize = HaType.Body,
            fontWeight = RemoteFontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
          )
          if (data.showState) {
            RemoteText(
              text = data.state,
              modifier = RemoteModifier.padding(start = 8.rdp),
              color = theme.secondaryText,
              fontSize = HaType.Label,
              style = haTextStyle(),
              maxLines = 1,
              overflow = RemoteTextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

/**
 * The picture at [url] as the embedding surface encodes it ([LocalPictureImageStrategy]): a named
 * URL the player fetches, or bytes a widget prefetched; null when an inline surface has none. Named
 * by [pictureEntityId] when the picture is an entity's (`entity_picture`), so the name survives a
 * rotated token, else by the URL itself.
 */
@Composable
internal fun rememberPictureBitmap(url: String, pictureEntityId: String?): RemoteImageBitmap? {
  val bindingName = pictureEntityId?.let(::pictureBindingName) ?: url
  return when (val strategy = LocalPictureImageStrategy.current) {
    is PictureImageStrategy.Url ->
      rememberLocalNamedRemoteBitmap(
        name = bindingName,
        url = url,
        width = strategy.widthPx,
        height = strategy.heightPx,
      )
    is PictureImageStrategy.Inline ->
      strategy.bitmapFor(url)?.let { bytes ->
        rememberLocalNamedRemoteBitmap(name = bindingName) { bytes }
      }
  }
}
