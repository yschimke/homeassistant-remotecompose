@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.border
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.rdp

/**
 * Full-canvas card surface for host-sized widget captures.
 *
 * [cardChrome] wraps a card's *content* — its rounded clip, fill, and border hug whatever the card
 * draws, so wherever the launcher cell is taller (or wider) than the content the surrounding band
 * is left unpainted. That blank band is exactly what shows up behind a pinned widget that hasn't
 * grown to fill its slot.
 *
 * This composable instead fills the **entire capture canvas** with the themed card surface (rounded
 * clip + [RemoteHaTheme.cardBackground] + hairline [RemoteHaTheme.divider] border) and renders
 * [content] on top, top-start aligned. The background therefore always matches the full current
 * widget size regardless of how much the inner card draws.
 *
 * Pair with `ProvideCardChrome(enabled = false)` at the capture root so the inner card skips its
 * own content-sized frame and doesn't double up inside this one — mirroring how the Wear slot
 * surface already suppresses per-card chrome. Used by the launcher widget
 * (`TerrazzoWidgetProvider`).
 *
 * The whole surface taps as [WidgetOpenApp] when it is captured for a launcher (a widget action
 * registry is in scope). The card's own controls sit inside it and take their taps first.
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
  val openApp = if (isWidgetActionCapture()) WidgetOpenApp.toRemoteAction() else null
  RemoteBox(
    modifier =
      modifier
        .fillMaxSize()
        .let { if (openApp != null) it.clickable(openApp) else it }
        .clip(shape)
        .background(theme.cardBackground)
        .border(borderWidthDp.rdp, theme.divider, shape),
  ) {
    content()
  }
}
