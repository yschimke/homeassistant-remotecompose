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
 * [initiallyOn]; without a second glyph, [icon]. Callers pass the component's existing bindings: a
 * second named `RemoteBoolean` for the same entity would collide.
 *
 * `is_on` is false for an unavailable or unknown entity too, where Home Assistant draws the rule's
 * default glyph. When that default is the on glyph ([activeWhenUnavailable]: a light's `lightbulb`,
 * not `lightbulb-off`), the on glyph also shows while [isAvailable] (`<entityId>.is_available`) is
 * false. Without that binding (widgets carry none, see [LocalEntityAvailability]) the choice is
 * baked from [initiallyAvailable].
 */
@Composable
@RemoteComposable
internal fun HaStateIcon(
  icon: ImageVector,
  activeIcon: ImageVector?,
  initiallyOn: Boolean,
  isOn: RemoteBoolean?,
  activeWhenUnavailable: Boolean,
  initiallyAvailable: Boolean,
  isAvailable: RemoteBoolean?,
  tint: RemoteColor,
  contentDescription: RemoteString?,
  modifier: RemoteModifier,
) {
  if (activeIcon == null || activeIcon == icon) {
    RemoteIcon(icon.toRemoteImageVector(), contentDescription, modifier, tint = tint)
    return
  }
  val unavailableShowsActive = activeWhenUnavailable && !initiallyAvailable
  if (isOn == null || (isAvailable == null && unavailableShowsActive)) {
    val glyph = if (initiallyOn || unavailableShowsActive) activeIcon else icon
    RemoteIcon(glyph.toRemoteImageVector(), contentDescription, modifier, tint = tint)
    return
  }
  val showActive =
    if (activeWhenUnavailable && isAvailable != null) isOn.or(isAvailable.not()) else isOn
  val hidden = Color.Transparent.rc
  RemoteBox(contentAlignment = RemoteAlignment.Center) {
    RemoteIcon(
      icon.toRemoteImageVector(),
      contentDescription,
      modifier,
      tint = showActive.select(hidden, tint),
    )
    // Described once: both glyphs are drawn, one transparent, so a description on each would be
    // read twice.
    RemoteIcon(
      activeIcon.toRemoteImageVector(),
      null,
      modifier,
      tint = showActive.select(tint, hidden),
    )
  }
}

/** [HaStateIcon] for an entity component, from its [accent] and the bindings in its [look]. */
@Composable
@RemoteComposable
internal fun HaStateIcon(
  icon: ImageVector,
  accent: HaToggleAccent,
  look: HaEntityLook,
  tint: RemoteColor,
  contentDescription: RemoteString?,
  modifier: RemoteModifier,
) =
  HaStateIcon(
    icon = icon,
    activeIcon = accent.activeIcon,
    initiallyOn = accent.initiallyOn,
    isOn = look.isOn,
    activeWhenUnavailable = accent.activeIconWhenUnavailable,
    initiallyAvailable = accent.initiallyAvailable,
    isAvailable = look.isAvailable,
    tint = tint,
    contentDescription = contentDescription,
    modifier = modifier,
  )

/**
 * `<entityId>.is_available` for a component that reads availability only for an on/off glyph pair
 * ([activeWhenUnavailable]); null when it doesn't need it or availability is off
 * ([LocalEntityAvailability], widgets).
 */
@Composable
internal fun pairAvailability(
  entityId: String?,
  activeWhenUnavailable: Boolean,
  initiallyAvailable: Boolean,
): RemoteBoolean? =
  if (activeWhenUnavailable && LocalEntityAvailability.current) {
    LiveValues.isAvailable(entityId, initiallyAvailable)
  } else {
    null
  }
