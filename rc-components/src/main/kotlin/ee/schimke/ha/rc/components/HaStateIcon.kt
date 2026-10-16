@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.wear.compose.remote.material3.RemoteIcon

/**
 * An entity's icon that follows its on/off state: [icon] while off, [activeIcon] (usually
 * [HaToggleAccent.activeIcon]) while on.
 *
 * With the [isOn] binding both glyphs are baked, stacked, and each is tinted [tint] only in its own
 * state (transparent otherwise), so a host's `is_on` push swaps them without a re-encode — the same
 * colour-select the dials use to hide a marker. Without a binding it is the glyph for
 * [initiallyOn]; without a second glyph, [icon]. Callers pass the component's existing `is_on`
 * binding: a second named `RemoteBoolean` for the same entity would collide.
 */
@Composable
@RemoteComposable
internal fun HaStateIcon(
  icon: ImageVector,
  activeIcon: ImageVector?,
  initiallyOn: Boolean,
  isOn: RemoteBoolean?,
  tint: RemoteColor,
  contentDescription: RemoteString,
  modifier: RemoteModifier,
) {
  if (activeIcon == null || activeIcon == icon) {
    RemoteIcon(icon.toRemoteImageVector(), contentDescription, modifier, tint = tint)
    return
  }
  if (isOn == null) {
    val glyph = if (initiallyOn) activeIcon else icon
    RemoteIcon(glyph.toRemoteImageVector(), contentDescription, modifier, tint = tint)
    return
  }
  val hidden = Color.Transparent.rc
  RemoteBox(contentAlignment = RemoteAlignment.Center) {
    RemoteIcon(
      icon.toRemoteImageVector(),
      contentDescription,
      modifier,
      tint = isOn.select(hidden, tint),
    )
    RemoteIcon(
      activeIcon.toRemoteImageVector(),
      contentDescription,
      modifier,
      tint = isOn.select(tint, hidden),
    )
  }
}
