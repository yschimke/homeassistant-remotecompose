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
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
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
 * `picture` card — a static image with optional name overlay. RC
 * alpha08 has no channel for arbitrary HTTP images, so the renderer
 * paints a tinted placeholder + caption. Hosts that wire an image
 * channel later can replace the placeholder without changing the
 * converter / model boundary.
 */
@Composable
@RemoteComposable
fun RemoteHaPicture(data: HaPictureCardData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val click = data.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    RemoteBox(
        modifier = modifier
            .then(click)
            .fillMaxWidth()
            .height(140.rdp)
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.divider.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp)),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteIcon(
            imageVector = data.placeholderIcon,
            contentDescription = data.name ?: "image".rs,
            modifier = RemoteModifier.size(48.rdp),
            tint = theme.placeholderAccent.rc,
        )
        if (data.name != null) {
            RemoteBox(
                modifier = RemoteModifier
                    .fillMaxWidth()
                    .background(theme.cardBackground.rc.copy(alpha = theme.cardBackground.rc.alpha * 0.85f.rf))
                    .padding(horizontal = 12.rdp, vertical = 6.rdp),
            ) {
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
        if (data.captionUrl != null) {
            RemoteBox(
                modifier = RemoteModifier
                    .padding(8.rdp)
                    .clip(RemoteRoundedCornerShape(4.rdp))
                    .background(theme.cardBackground.rc.copy(alpha = theme.cardBackground.rc.alpha * 0.7f.rf))
                    .padding(horizontal = 6.rdp, vertical = 2.rdp),
            ) {
                RemoteText(
                    text = data.captionUrl,
                    color = theme.secondaryText.rc,
                    fontSize = 9.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * `picture-glance` card — image with a strip of clickable entity cells
 * across the bottom. Same image-channel limitation as [RemoteHaPicture].
 */
@Composable
@RemoteComposable
fun RemoteHaPictureGlance(
    data: HaPictureGlanceData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.divider.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp)),
    ) {
        RemoteColumn {
            RemoteBox(
                modifier = RemoteModifier
                    .fillMaxWidth()
                    .height(120.rdp)
                    .background(theme.divider.rc),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = data.placeholderIcon,
                    contentDescription = data.title ?: "image".rs,
                    modifier = RemoteModifier.size(40.rdp),
                    tint = theme.placeholderAccent.rc,
                )
            }
            RemoteRow(
                modifier = RemoteModifier
                    .fillMaxWidth()
                    .background(theme.cardBackground.rc)
                    .padding(horizontal = 10.rdp, vertical = 8.rdp),
                horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
                verticalAlignment = RemoteAlignment.CenterVertically,
            ) {
                if (data.title != null) {
                    RemoteText(
                        text = data.title,
                        color = theme.primaryText.rc,
                        fontSize = 13.rsp,
                        fontWeight = FontWeight.Medium,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                data.cells.forEach { cell -> Cell(cell, theme) }
            }
        }
    }
}

@Composable
private fun Cell(cell: HaPictureGlanceCell, theme: HaTheme) {
    val click = cell.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val accent = cell.accent.rc
    RemoteBox(
        modifier = RemoteModifier.then(click)
            .size(32.rdp)
            .clip(RemoteCircleShape)
            .background(
                if (cell.isActive) accent.copy(alpha = accent.alpha * 0.18f.rf)
                else theme.divider.rc.copy(alpha = theme.divider.rc.alpha * 0.5f.rf),
            ),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteIcon(
            imageVector = cell.icon,
            contentDescription = cell.label,
            modifier = RemoteModifier.size(18.rdp),
            tint = if (cell.isActive) accent else theme.secondaryText.rc,
        )
    }
}

/**
 * `picture-elements` card — image plus a list of elements. v1 lays the
 * elements out as a chip strip below the image; precise XY positioning
 * needs absolute placement that RC's RemoteBox parent doesn't expose
 * cleanly (follow-up).
 */
@Composable
@RemoteComposable
fun RemoteHaPictureElements(
    data: HaPictureElementsData,
    modifier: RemoteModifier = RemoteModifier,
) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .clip(RemoteRoundedCornerShape(12.rdp))
            .background(theme.cardBackground.rc)
            .border(1.rdp, theme.divider.rc, RemoteRoundedCornerShape(12.rdp)),
    ) {
        RemoteColumn {
            RemoteBox(
                modifier = RemoteModifier
                    .fillMaxWidth()
                    .height(120.rdp)
                    .background(theme.divider.rc),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = data.placeholderIcon,
                    contentDescription = "elements".rs,
                    modifier = RemoteModifier.size(40.rdp),
                    tint = theme.placeholderAccent.rc,
                )
            }
            if (data.elements.isNotEmpty()) {
                RemoteRow(
                    modifier = RemoteModifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.rdp, vertical = 8.rdp),
                    horizontalArrangement = RemoteArrangement.spacedBy(8.rdp, RemoteAlignment.Start),
                    verticalAlignment = RemoteAlignment.CenterVertically,
                ) {
                    data.elements.forEach { Element(it, theme) }
                }
            }
        }
    }
}

@Composable
private fun Element(element: HaPictureElement, theme: HaTheme) {
    when (element) {
        is HaPictureElement.StateIcon -> {
            val click = element.tapAction.toRemoteAction()
                ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
            val accent = element.accent.rc
            RemoteBox(
                modifier = RemoteModifier.then(click)
                    .size(28.rdp)
                    .clip(RemoteCircleShape)
                    .background(
                        if (element.isActive) accent.copy(alpha = accent.alpha * 0.18f.rf)
                        else theme.divider.rc.copy(alpha = theme.divider.rc.alpha * 0.5f.rf),
                    ),
                contentAlignment = RemoteAlignment.Center,
            ) {
                RemoteIcon(
                    imageVector = element.icon,
                    contentDescription = "element".rs,
                    modifier = RemoteModifier.size(16.rdp),
                    tint = if (element.isActive) accent else theme.secondaryText.rc,
                )
            }
        }
        is HaPictureElement.StateLabel -> {
            RemoteText(
                text = element.text,
                color = theme.primaryText.rc,
                fontSize = 12.rsp,
                style = RemoteTextStyle.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        is HaPictureElement.ServiceButton -> {
            val click = element.tapAction.toRemoteAction()
                ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
            val accent = element.accent.rc
            RemoteBox(
                modifier = RemoteModifier.then(click)
                    .clip(RemoteRoundedCornerShape(6.rdp))
                    .border(1.rdp, accent, RemoteRoundedCornerShape(6.rdp))
                    .padding(horizontal = 10.rdp, vertical = 6.rdp),
            ) {
                RemoteText(
                    text = element.label,
                    color = accent,
                    fontSize = 11.rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                )
            }
        }
    }
}
