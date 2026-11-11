@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.layout.RemoteContentScale
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteImage
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.border
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.offset
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon
import androidx.compose.runtime.Composable

/**
 * `picture` card — a static image with optional name overlay. RC alpha08 has no channel for
 * arbitrary HTTP images, so the renderer paints a tinted placeholder + caption. Hosts that wire an
 * image channel later can replace the placeholder without changing the converter / model boundary.
 *
 * [fillHeight] fills a host-fixed cell; otherwise a non-null [aspectRatio] (width ÷ height) sizes
 * the card's height from its live width, and null keeps the legacy fixed 140 dp band.
 */
@Composable
@RemoteComposable
fun RemoteHaPicture(
  data: HaPictureCardData,
  modifier: RemoteModifier = RemoteModifier,
  fillHeight: Boolean = false,
  aspectRatio: Float? = null,
) {
  // Wrap with a ratio: height follows the live width (HA's `aspect_ratio`).
  if (!fillHeight && aspectRatio != null) {
    RemoteAspectRatioBox(aspectRatio, modifier) {
      RemoteHaPicture(data, RemoteModifier.fillMaxSize(), fillHeight = true)
    }
    return
  }
  val theme = haTheme()
  val click =
    data.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  // Wrap: fixed natural band. Fixed: fill the whole cell so the image
  // (placeholder until a host wires an image channel) is the identity
  // edge-to-edge with the name as a bottom scrim.
  val sizeModifier =
    if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth().height(140.rdp)
  RemoteBox(
    modifier =
      modifier.then(click).then(sizeModifier).then(cardChrome(theme.divider, theme.divider)),
    contentAlignment = RemoteAlignment.Center,
  ) {
    if (haLayouts().notes == HaNoteStyle.Tape) {
      RemoteColumn(
        horizontalAlignment = RemoteAlignment.CenterHorizontally,
        verticalArrangement = RemoteArrangement.spacedBy(6.rdp),
        modifier = RemoteModifier.padding(8.rdp),
      ) {
        ConsoleTapeLabel("Image unavailable".rs)
        data.name?.let { ConsoleTapeLabel(cardText("name", it), compact = true) }
        data.captionUrl?.let { ConsoleTapeLabel(cardText("image", it), compact = true) }
      }
    } else {
      // The icon in the middle of what the name leaves, the name a strip along the bottom: stacked
      // in the one box, the strip sat over the icon and the image path over the strip.
      RemoteColumn(modifier = RemoteModifier.fillMaxSize()) {
        RemoteBox(
          modifier = RemoteModifier.fillMaxWidth().weight(1f.rf),
          contentAlignment = RemoteAlignment.Center,
        ) {
          RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
            RemoteBasicIcon(
              imageVector = data.placeholderIcon.toRemoteImageVector(),
              contentDescription = data.name?.let { cardText("name", it) } ?: "image".rs,
              modifier = RemoteModifier.size(48.rdp),
              tint = theme.placeholderAccent,
            )
            // The image's path, only where no name says what the picture is.
            if (data.captionUrl != null && data.name == null) {
              RemoteBox(
                modifier =
                  RemoteModifier.padding(8.rdp)
                    .clip(RemoteRoundedCornerShape(4.rdp))
                    .background(
                      theme.cardBackground.copy(alpha = theme.cardBackground.alpha * 0.7f.rf),
                    )
                    .padding(horizontal = 6.rdp, vertical = 2.rdp),
              ) {
                RemoteText(
                  text = cardText("image", data.captionUrl),
                  color = theme.secondaryText,
                  fontSize = HaType.Micro,
                  style = haTextStyle(),
                  maxLines = 1,
                  overflow = RemoteTextOverflow.Ellipsis,
                )
              }
            }
          }
        }
        if (data.name != null) {
          RemoteBox(
            modifier =
              RemoteModifier.fillMaxWidth()
                .background(
                  theme.cardBackground.copy(alpha = theme.cardBackground.alpha * 0.85f.rf),
                )
                .padding(horizontal = 12.rdp, vertical = 6.rdp),
          ) {
            RemoteText(
              text = cardText("name", data.name),
              color = theme.primaryText,
              fontSize = HaType.Body,
              fontWeight = RemoteFontWeight.Medium,
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
 * `picture-glance` card — image with a strip of clickable entity cells across the bottom. Same
 * image-channel limitation as [RemoteHaPicture]. [imageAspectRatio] (width ÷ height) sizes the
 * image area from the card's live width; the strip sits below it.
 */
@Composable
@RemoteComposable
fun RemoteHaPictureGlance(
  data: HaPictureGlanceData,
  modifier: RemoteModifier = RemoteModifier,
  imageAspectRatio: Float? = null,
  fillHeight: Boolean = false,
  showImage: Boolean = true,
) {
  val theme = haTheme()
  RemoteBox(
    modifier =
      (if (fillHeight) modifier.fillMaxSize() else modifier.fillMaxWidth()).then(
        cardChrome(theme.divider, theme.divider),
      ),
  ) {
    RemoteColumn(modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier) {
      // The image area: in a fixed cell ([fillHeight]) whatever the strip leaves, so the card
      // stays inside the cell; else its height follows the live width at [imageAspectRatio], or
      // the legacy fixed 120 dp band when there is none. [showImage] false leaves the strip alone,
      // for a cell too short for both.
      if (!showImage) {
        // The strip only.
      } else if (fillHeight) {
        GlanceImage(data, theme, RemoteModifier.fillMaxWidth().weight(1f.rf))
      } else if (imageAspectRatio != null) {
        RemoteAspectRatioBox(imageAspectRatio) {
          GlanceImage(data, theme, RemoteModifier.fillMaxSize())
        }
      } else {
        GlanceImage(data, theme, RemoteModifier.fillMaxWidth().height(120.rdp))
      }
      // The cells' 48dp touch targets fill the strip's 8dp vertical inset and sit edge to edge, so
      // no tap is shared between two entities (the accessibility checker counts only a target's
      // unshared area): the 32dp circles end up 16dp apart. The row's offset keeps the first circle
      // where it was.
      RemoteRow(
        modifier =
          RemoteModifier.fillMaxWidth()
            .background(theme.cardBackground)
            .padding(horizontal = 10.rdp),
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        if (data.title != null) {
          // The title takes what the cells leave: the cells are the controls, so a narrow strip
          // shortens the title rather than pushing a cell out.
          RemoteText(
            text = cardTitle(data.title),
            color = theme.primaryText,
            fontSize = HaType.Body,
            fontWeight = RemoteFontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = RemoteTextOverflow.Ellipsis,
            // The gap is the title's padding: spacedBy isn't taken out of a weighted share.
            modifier = RemoteModifier.weight(1f.rf).padding(end = 8.rdp),
          )
        }
        if (data.cells.isNotEmpty()) {
          RemoteRow(
            modifier = RemoteModifier.offset((-CELL_TARGET_INSET_DP).rdp, 0.rdp),
            verticalAlignment = RemoteAlignment.CenterVertically,
          ) {
            data.cells.forEach { cell -> Cell(cell, theme) }
          }
        }
      }
    }
  }
}

@Composable
@RemoteComposable
private fun GlanceImage(data: HaPictureGlanceData, theme: RemoteHaTheme, modifier: RemoteModifier) {
  // Under a kit with display windows a camera's feed is in one, as its readouts are.
  if (cameraInLcdWindow(data.pictureEntityId)) {
    LcdPictureWindow(modifier) { GlancePicture(data, theme) }
    return
  }
  RemoteBox(
    modifier = modifier.background(theme.divider),
    contentAlignment = RemoteAlignment.Center,
  ) {
    GlancePicture(data, theme)
  }
}

/** The glance's picture over its placeholder, centred in its area. */
@Composable
@RemoteComposable
private fun GlancePicture(data: HaPictureGlanceData, theme: RemoteHaTheme) {
  // The placeholder first, the picture over it: a picture that can't load (no loader, offline)
  // leaves the placeholder showing rather than an empty band.
  if (haLayouts().notes == HaNoteStyle.Tape) ConsoleTapeLabel("Image unavailable".rs)
  else
    RemoteBasicIcon(
      imageVector = data.placeholderIcon.toRemoteImageVector(),
      contentDescription = data.title?.let { cardTitle(it) } ?: "image".rs,
      modifier = RemoteModifier.size(40.rdp),
      tint = theme.placeholderAccent,
    )
  data.imageUrl
    ?.let { rememberPictureBitmap(it, data.pictureEntityId) }
    ?.let { picture ->
      RemoteImage(
        bitmap = picture,
        contentDescription = data.title?.let { cardTitle(it) } ?: "image".rs,
        modifier = RemoteModifier.fillMaxSize(),
        // Fill-crop, as HA's picture cards cover their area.
        contentScale = RemoteContentScale.Crop,
      )
    }
}

@Composable
private fun Cell(cell: HaPictureGlanceCell, theme: RemoteHaTheme) {
  val accent = haInk(cell.accent)
  val activeBg: RemoteColor = accent.copy(alpha = accent.alpha * 0.18f.rf)
  val inactiveBg: RemoteColor = theme.divider.copy(alpha = theme.divider.alpha * 0.5f.rf)

  // Live `<entityId>.is_on` so the host can flip the cell styling
  // without a re-encode; falls back to the authoring-time seed when
  // entityId is null (preview).
  val isActive = LiveValues.isOn(cell.entityId, cell.initiallyActive)
  val bg: RemoteColor =
    isActive?.select(activeBg, inactiveBg) ?: if (cell.initiallyActive) activeBg else inactiveBg
  val tint: RemoteColor =
    isActive?.select(accent, theme.secondaryText)
      ?: if (cell.initiallyActive) accent else theme.secondaryText

  RemoteHaTouchTarget(
    CELL_DP,
    CELL_DP,
    cell.tapAction.toRemoteAction(),
    entityName(cell.entityId, cell.label),
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.size(CELL_DP.rdp)
          .clip(haControlShape(16, round = true))
          .background(bg)
          .then(haKeyChrome(tint)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      HaStateIcon(
        icon = cell.icon,
        activeIcon = cell.activeIcon,
        initiallyOn = cell.initiallyActive,
        isOn = isActive,
        activeWhenUnavailable = cell.activeIconWhenUnavailable,
        initiallyAvailable = cell.initiallyAvailable,
        isAvailable =
          pairAvailability(cell.entityId, cell.activeIconWhenUnavailable, cell.initiallyAvailable),
        tint = tint,
        contentDescription = null,
        modifier = RemoteModifier.size(18.rdp),
      )
    }
  }
}

/** A picture glance cell's drawn circle, and how far its touch target reaches past it each side. */
private const val CELL_DP = 32
private const val CELL_TARGET_INSET_DP = (MinTouchTargetDp - CELL_DP) / 2

// HA's `style.top` / `style.left` are CSS percentages of the
// parent's box. RC has no fraction-based offset, so we lock the
// picture-elements canvas to a fixed dp size and convert percent →
// dp at encode time. 360x180 matches `naturalHeightDp` and the
// preview width; hosts that resize the card will see elements
// pinned to the top-left of this canvas rather than scaling.
private const val PICTURE_ELEMENTS_CANVAS_W_DP = 360
private const val PICTURE_ELEMENTS_CANVAS_H_DP = 180

/**
 * `picture-elements` card — image with elements overlaid at the `(top%, left%)` positions HA
 * configures. Elements without a position fall back to a strip at the bottom of the canvas.
 */
@Composable
@RemoteComposable
fun RemoteHaPictureElements(
  data: HaPictureElementsData,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  val (positioned, unpositioned) = data.elements.partition { it.position != null }
  RemoteBox(
    modifier =
      modifier
        .fillMaxWidth()
        .height(PICTURE_ELEMENTS_CANVAS_H_DP.rdp)
        .then(cardChrome(theme.divider, theme.divider)),
    contentAlignment = RemoteAlignment.TopStart,
  ) {
    RemoteBox(
      modifier = RemoteModifier.fillMaxWidth().height(PICTURE_ELEMENTS_CANVAS_H_DP.rdp),
      contentAlignment = RemoteAlignment.Center,
    ) {
      if (haLayouts().notes == HaNoteStyle.Tape) ConsoleTapeLabel("Image unavailable".rs)
      else
        RemoteBasicIcon(
          imageVector = data.placeholderIcon.toRemoteImageVector(),
          contentDescription = "elements".rs,
          modifier = RemoteModifier.size(40.rdp),
          tint = theme.placeholderAccent,
        )
    }
    positioned.forEach { element ->
      val pos = element.position!!
      val leftDp = (pos.leftFraction * PICTURE_ELEMENTS_CANVAS_W_DP).toInt().coerceAtLeast(0)
      val topDp = (pos.topFraction * PICTURE_ELEMENTS_CANVAS_H_DP).toInt().coerceAtLeast(0)
      Element(
        index = data.elements.indexOf(element),
        element = element,
        theme = theme,
        modifier = RemoteModifier.padding(start = leftDp.rdp, top = topDp.rdp),
      )
    }
    if (unpositioned.isNotEmpty()) {
      RemoteRow(
        modifier =
          RemoteModifier.padding(top = (PICTURE_ELEMENTS_CANVAS_H_DP - 44).rdp)
            .fillMaxWidth()
            .background(theme.cardBackground.copy(alpha = theme.cardBackground.alpha * 0.85f.rf))
            .padding(horizontal = 10.rdp, vertical = 8.rdp),
        horizontalArrangement = RemoteArrangement.spacedBy(8.rdp, RemoteAlignment.Start),
        verticalAlignment = RemoteAlignment.CenterVertically,
      ) {
        unpositioned.forEach { Element(data.elements.indexOf(it), it, theme) }
      }
    }
  }
}

@Composable
private fun Element(
  index: Int,
  element: HaPictureElement,
  theme: RemoteHaTheme,
  modifier: RemoteModifier = RemoteModifier,
) {
  when (element) {
    is HaPictureElement.StateIcon -> {
      val click =
        element.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
      val accent = haInk(element.accent)
      val activeBg: RemoteColor = accent.copy(alpha = accent.alpha * 0.18f.rf)
      val inactiveBg: RemoteColor = theme.divider.copy(alpha = theme.divider.alpha * 0.5f.rf)

      // Live `<entityId>.is_on` so the host can flip the element
      // styling without a re-encode.
      val isActive = LiveValues.isOn(element.entityId, element.initiallyActive)
      val bg: RemoteColor =
        isActive?.select(activeBg, inactiveBg)
          ?: if (element.initiallyActive) activeBg else inactiveBg
      val tint: RemoteColor =
        isActive?.select(accent, theme.secondaryText)
          ?: if (element.initiallyActive) accent else theme.secondaryText

      RemoteBox(
        modifier =
          modifier
            .then(click)
            .size(28.rdp)
            .clip(haControlShape(14, round = true))
            .background(bg)
            .then(haKeyChrome(tint)),
        contentAlignment = RemoteAlignment.Center,
      ) {
        HaStateIcon(
          icon = element.icon,
          activeIcon = element.activeIcon,
          initiallyOn = element.initiallyActive,
          isOn = isActive,
          activeWhenUnavailable = element.activeIconWhenUnavailable,
          initiallyAvailable = element.initiallyAvailable,
          isAvailable =
            pairAvailability(
              element.entityId,
              element.activeIconWhenUnavailable,
              element.initiallyAvailable,
            ),
          tint = tint,
          contentDescription = "element".rs,
          modifier = RemoteModifier.size(16.rdp),
        )
      }
    }
    is HaPictureElement.StateLabel -> {
      RemoteText(
        text = element.text,
        color = theme.primaryText,
        fontSize = HaType.Label,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
        modifier = modifier,
      )
    }
    is HaPictureElement.ServiceButton -> {
      val click =
        element.tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
      val accent = haInk(element.accent)
      RemoteBox(
        modifier =
          modifier
            .then(click)
            .clip(haControlShape(6))
            .border(1.rdp, accent, haControlShape(6))
            .then(haKeyChrome(accent))
            .padding(horizontal = 10.rdp, vertical = 6.rdp),
      ) {
        RemoteText(
          text = cardText("el.$index.label", element.label),
          color = accent,
          fontSize = HaType.Caption,
          fontWeight = RemoteFontWeight.Medium,
          style = haTextStyle(),
          maxLines = 1,
        )
      }
    }
  }
}
