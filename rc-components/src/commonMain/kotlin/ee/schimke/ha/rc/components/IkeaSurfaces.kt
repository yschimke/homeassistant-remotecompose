@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable

/** KALLAX's substantial square frame, inset laminate face and fine timber edge, in theme roles. */
internal fun ikeaFrameChrome(border: RemoteColor): RemoteModifier {
  return RemoteModifier.drawWithContent {
    drawContent()
    // The substantial shelf edge is distinct from the inset face. Fine grain stays at the edge,
    // away from labels and readings; the supplied border follows a widget's host palette too.
    val rail = border.copy(alpha = 0.45f.rf)
    fillRect(rail, 0f.rf, 0f.rf, width, 5f.rf)
    fillRect(rail, 0f.rf, height - 5f.rf, width, 5f.rf)
    fillRect(rail, 0f.rf, 0f.rf, 5f.rf, height)
    fillRect(rail, width - 5f.rf, 0f.rf, 5f.rf, height)
    for (x in listOf(2f, 4f)) {
      val color = border.copy(alpha = if (x == 2f) 0.4f.rf else 0.7f.rf)
      fillRect(color, x.rf, x.rf, width - (x * 2).rf, 0.6f.rf)
      fillRect(color, x.rf, height - (x + 1).rf, width - (x * 2).rf, 0.6f.rf)
      fillRect(color, x.rf, x.rf, 0.6f.rf, height - (x * 2).rf)
      fillRect(color, width - (x + 1).rf, x.rf, 0.6f.rf, height - (x * 2).rf)
    }
  }
}

/** Moulded remote key. Its caller owns action, accessible name and touch area. */
@Composable
internal fun ikeaKeyChrome(accent: RemoteColor? = null): RemoteModifier {
  val theme = haTheme()
  return RemoteModifier.drawWithContent {
    fillRound(theme.divider, 0f.rf, 1f.rf, width, height - 1f.rf, 5f.rf)
    fillRound(theme.placeholderBackground, 1f.rf, 1f.rf, width - 2f.rf, height - 4f.rf, 4f.rf)
    fillRect(theme.cardBackground, 5f.rf, 2f.rf, width - 10f.rf, 1f.rf)
    accent?.let { fillRound(it, width * 0.35f.rf, height - 6f.rf, width * 0.3f.rf, 2f.rf, 1f.rf) }
    drawContent()
  }
}

/** BILRESA-like two-part paddle: the live indicator moves between the two halves. */
@Composable
@RemoteComposable
internal fun IkeaControlFace(accent: RemoteColor, progress: RemoteFloat, modifier: RemoteModifier) {
  val theme = haTheme()
  RemoteCanvas(modifier) {
    fillRound(theme.divider, 0f.rf, 0f.rf, width, height, 5f.rf)
    fillRound(theme.placeholderBackground, 1f.rf, 1f.rf, width - 2f.rf, height - 3f.rf, 4f.rf)
    fillRect(theme.divider, width / 2f.rf, 3f.rf, 1f.rf, height - 6f.rf)
    val lamp = RemoteOffset(width * 0.75f.rf - width * 0.5f.rf * progress, height * 0.72f.rf)
    drawCircle(fillPaint(accent), 2f.rf, lamp)
    // I / O remain readable without colour; fill and position communicate the live state.
    fillRect(theme.primaryText, width * 0.25f.rf, height * 0.2f.rf, 1.5f.rf, height * 0.22f.rf)
    drawCircle(
      strokePaint(1.2f, theme.primaryText),
      height * 0.1f.rf,
      RemoteOffset(width * 0.75f.rf, height * 0.31f.rf),
    )
  }
}
