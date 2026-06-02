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
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * HA `picture-entity` card — modern image-with-overlay tile
 * (`hui-picture-entity-card.ts`).
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
 * RemoteCompose alpha08 doesn't accept arbitrary HTTP image references
 * at capture time, so the image area renders a flat tinted placeholder
 * with a domain-appropriate icon. The bottom bar matches HA's standard
 * `hui-image-overlay`.
 *
 * The image is the card's identity at every size: it **fills and crops**
 * to the cell ([ContentScale.Crop]) so it never letterboxes, with the
 * name/state strip kept as a translucent scrim along the bottom. In
 * [fillHeight] mode (launcher / Wear Fixed cells) the card grows to the
 * full cell height instead of the [naturalHeightDp]-derived 160 dp the
 * dashboard wraps to — so the picture earns the whole canvas rather than
 * letterboxing into a band (see
 * docs/architecture/adaptive-card-layouts.md §"Picture family").
 */
@Composable
@RemoteComposable
fun RemoteHaPictureEntity(
    data: HaPictureEntityData,
    modifier: RemoteModifier = RemoteModifier,
    fillHeight: Boolean = false,
) {
    val theme = haTheme()
    val isOnBinding =
        if (data.accent.toggleable) LiveValues.isOn(data.entityId, data.accent.initiallyOn) else null
    val accent =
        isOnBinding?.select(data.accent.activeAccent, data.accent.inactiveAccent)
            ?: data.accent.activeAccent
    val clickable = data.tapAction.toRemoteAction()
        ?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
    val showStrip = data.showName || data.showState

    // Wrap: width pinned, fixed natural height (HA reference band). Fixed:
    // fill the whole cell so the image is the identity edge-to-edge.
    val sizeModifier =
        if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth().height(160.rdp)

    RemoteBox(
        modifier = modifier
            .then(clickable)
            .then(sizeModifier)
            .then(cardChrome(theme.divider, theme.divider)),
    ) {
        RemoteColumn(modifier = RemoteModifier.fillMaxSize()) {
            RemoteBox(
                modifier = RemoteModifier
                    .fillMaxWidth()
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
                    val bindingName =
                        data.entityId?.let(::pictureBindingName) ?: data.imageUrl
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
                            contentDescription = data.name.rs,
                            modifier = RemoteModifier.fillMaxSize(),
                            // Fill-crop, not letterbox: the image owns the
                            // whole cell at any aspect (HA's `cover`).
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        // Inline strategy with no pre-fetched bytes for
                        // this URL — fall back to the icon path so the
                        // tile renders something instead of a hole.
                        RemoteIcon(
                            imageVector = data.icon,
                            contentDescription = data.name.rs,
                            modifier = RemoteModifier.size(40.rdp),
                            tint = accent.copy(alpha = accent.alpha * 0.55f.rf),
                        )
                    }
                } else {
                    RemoteIcon(
                        imageVector = data.icon,
                        contentDescription = data.name.rs,
                        modifier = RemoteModifier.size(40.rdp),
                        tint = accent.copy(alpha = accent.alpha * 0.55f.rf),
                    )
                }
                if (data.frameStamp != null) {
                    RemoteBox(
                        modifier = RemoteModifier
                            .padding(8.rdp)
                            .background(theme.cardBackground.rc.copy(alpha = theme.cardBackground.rc.alpha * 0.8f.rf))
                            .padding(horizontal = 6.rdp, vertical = 2.rdp),
                    ) {
                        RemoteText(
                            text = data.frameStamp.rs,
                            color = theme.secondaryText.rc,
                            fontSize = 10.rsp,
                            style = RemoteTextStyle.Default,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (showStrip) {
                val barBg = theme.cardBackground.rc
                RemoteRow(
                    modifier = RemoteModifier
                        .fillMaxWidth()
                        .background(barBg.copy(alpha = barBg.alpha * 0.85f.rf))
                        .padding(horizontal = 12.rdp, vertical = 8.rdp),
                    verticalAlignment = RemoteAlignment.CenterVertically,
                    horizontalArrangement = RemoteArrangement.SpaceBetween,
                ) {
                    RemoteText(
                        text = (if (data.showName) data.name else "").rs,
                        color = theme.primaryText.rc,
                        fontSize = 13.rsp,
                        fontWeight = FontWeight.Medium,
                        style = RemoteTextStyle.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (data.showState) {
                        RemoteText(
                            text = data.state,
                            color = theme.secondaryText.rc,
                            fontSize = 12.rsp,
                            style = RemoteTextStyle.Default,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
