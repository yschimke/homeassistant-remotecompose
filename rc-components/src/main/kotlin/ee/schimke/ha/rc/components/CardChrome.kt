@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.border
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
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
 * default 12.rdp corner and 1.rdp border match the shape every built-in HA card draws today.
 */
@Composable
@ReadOnlyComposable
fun cardChrome(
  background: Color,
  border: Color,
  cornerRadiusDp: Int = 12,
  borderWidthDp: Int = 1,
): RemoteModifier {
  if (!LocalCardChromeEnabled.current) return RemoteModifier
  val shape = RemoteRoundedCornerShape(cornerRadiusDp.rdp)
  return RemoteModifier.clip(shape)
    .background(background.rc)
    .border(borderWidthDp.rdp, border.rc, shape)
}
