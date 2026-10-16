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
import androidx.compose.remote.creation.compose.state.ri
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
 *
 * `is_on` is false for an unavailable or unknown entity too, where Home Assistant draws the rule's
 * default glyph. When that default is the on glyph ([activeWhenUnavailable]: a light's `lightbulb`,
 * not `lightbulb-off`), the on glyph also shows while `<entityId>.status` (pushed by every host,
 * `CardDesignSlots.statusOf`) isn't 0 — [initiallyUnavailable] seeds it.
 */
@Composable
@RemoteComposable
internal fun HaStateIcon(
  icon: ImageVector,
  activeIcon: ImageVector?,
  initiallyOn: Boolean,
  isOn: RemoteBoolean?,
  entityId: String?,
  activeWhenUnavailable: Boolean,
  initiallyUnavailable: Boolean,
  tint: RemoteColor,
  contentDescription: RemoteString,
  modifier: RemoteModifier,
) {
  if (activeIcon == null || activeIcon == icon) {
    RemoteIcon(icon.toRemoteImageVector(), contentDescription, modifier, tint = tint)
    return
  }
  if (isOn == null) {
    val active = initiallyOn || (activeWhenUnavailable && initiallyUnavailable)
    val glyph = if (active) activeIcon else icon
    RemoteIcon(glyph.toRemoteImageVector(), contentDescription, modifier, tint = tint)
    return
  }
  val showActive =
    if (activeWhenUnavailable && entityId != null) {
      val status = LiveValues.namedInt(entityId, "status", if (initiallyUnavailable) 1 else 0)
      isOn.or(status.isNotEqualTo(0.ri))
    } else {
      isOn
    }
  val hidden = Color.Transparent.rc
  RemoteBox(contentAlignment = RemoteAlignment.Center) {
    RemoteIcon(
      icon.toRemoteImageVector(),
      contentDescription,
      modifier,
      tint = showActive.select(hidden, tint),
    )
    RemoteIcon(
      activeIcon.toRemoteImageVector(),
      contentDescription,
      modifier,
      tint = showActive.select(tint, hidden),
    )
  }
}
