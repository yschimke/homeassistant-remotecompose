@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.verticalGradient
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Whether HA card components should draw their own outer chrome — the rounded-corner clip, themed
 * surface fill, and hairline divider border that frames each top-level card.
 *
 * Default is `true` so the in-app dashboard and the launcher widget (both flat parents) keep the
 * current look. Glance Wear widget surfaces flip this to `false`: the wear container already
 * supplies its own shape + brush, so the card's chrome would double up against the widget frame.
 */
val LocalCardChromeEnabled = staticCompositionLocalOf { true }

/** Wrap [content] so cards inside it either draw or skip their outer chrome. */
@Composable
fun ProvideCardChrome(enabled: Boolean, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalCardChromeEnabled provides enabled, content = content)
}

/**
 * Outer-card chrome modifier — rounded clip + background fill + hairline border. Returns a no-op
 * [RemoteModifier] when [LocalCardChromeEnabled] is `false` (inside a wear widget that already
 * provides the shape).
 *
 * Apply via `.then(cardChrome(...))` at the top-level card box, before its `.padding(...)`. The
 * corner and border default to the active uber theme's card shape ([LocalHaUberTheme]): 12dp and
 * 1dp for [HaUberTheme.Classic], the shape every built-in HA card has always drawn. A border of 0
 * draws none.
 */
@Composable
@ReadOnlyComposable
fun cardChrome(
  background: RemoteColor,
  border: RemoteColor,
  cornerRadiusDp: Int? = null,
  borderWidthDp: Int? = null,
): RemoteModifier {
  if (!LocalCardChromeEnabled.current) return RemoteModifier
  val style = LocalHaUberTheme.current.theme.shape
  val shape = RemoteRoundedCornerShape((cornerRadiusDp ?: style.cornerDp).rdp)
  val filled = RemoteModifier.clip(shape).background(background)
  // A painted-metal panel is lit from above: a sheen over whatever colour the card is, falling
  // into shade at the bottom edge. It is a fixed overlay, so a live background still shows through.
  val clipped =
    if (style.material == HaSurfaceMaterial.PaintedMetal) filled.background(panelSheen())
    else if (style.material == HaSurfaceMaterial.BirchFrame) filled.then(ikeaFrameChrome(border))
    else filled
  val borderDp = borderWidthDp ?: style.borderDp
  return if (borderDp > 0) clipped.border(borderDp.rdp, border, shape) else clipped
}

/** The light falling across a painted-metal panel: highlight at the top, shade at the bottom. */
private fun panelSheen(): RemoteBrush =
  RemoteBrush.verticalGradient(
    listOf(
      Color.White.copy(alpha = 0.10f).rc,
      Color.White.copy(alpha = 0.02f).rc,
      Color.Black.copy(alpha = 0.08f).rc,
      Color.Black.copy(alpha = 0.24f).rc,
    )
  )
