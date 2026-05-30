@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable

/**
 * Full-canvas card surface for host-sized widget captures.
 *
 * [cardChrome] wraps a card's *content* — its rounded clip, fill, and
 * border hug whatever the card draws, so wherever the launcher cell is
 * taller (or wider) than the content the surrounding band is left
 * unpainted. That blank band is exactly what shows up behind a pinned
 * widget that hasn't grown to fill its slot.
 *
 * This composable instead fills the **entire capture canvas** with the
 * themed card surface (rounded clip + [HaTheme.cardBackground] +
 * hairline [HaTheme.divider] border) and renders [content] on top,
 * top-start aligned. The background therefore always matches the full
 * current widget size regardless of how much the inner card draws.
 *
 * Pair with `ProvideCardChrome(enabled = false)` at the capture root so
 * the inner card skips its own content-sized frame and doesn't double
 * up inside this one — mirroring how the Wear slot surface already
 * suppresses per-card chrome. Used by the launcher widget
 * (`TerrazzoWidgetProvider`).
 */
@Composable
fun RemoteHaWidgetSurface(
    modifier: RemoteModifier = RemoteModifier,
    cornerRadiusDp: Int = 12,
    borderWidthDp: Int = 1,
    content: @Composable () -> Unit,
) {
    val theme = haTheme()
    val shape = RemoteRoundedCornerShape(cornerRadiusDp.rdp)
    RemoteBox(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(theme.cardBackground.rc)
            .border(borderWidthDp.rdp, theme.divider.rc, shape),
    ) {
        content()
    }
}
