@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.icons.mdi

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.capture.toRemoteImageVector
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.foundation.icon.RemoteBasicIcon
import io.github.timoptr.mdiicons.MdiIcon

/**
 * Draws an MDI [icon] into a Remote Compose document, tinted [tint]: one filled path on a 24×24
 * viewport, sized by [modifier] (24 dp by default). Use a compile-time accessor
 * (`RemoteMdiIcon(Mdi.HomeAssistant, …)`) for a fixed glyph, or the `name` overload for one Home
 * Assistant names at runtime.
 */
@Composable
@RemoteComposable
fun RemoteMdiIcon(
  icon: MdiIcon,
  contentDescription: RemoteString?,
  tint: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
) {
  RemoteBasicIcon(MdiIcons[icon].toRemoteImageVector(), contentDescription, modifier, tint = tint)
}

/**
 * [RemoteMdiIcon] for an icon named at runtime: `mdi:home`, `hass:fan` or a bare `lightbulb-on`.
 * Draws [fallback] when [name] isn't an MDI icon (another icon set, or a name MDI never had), and
 * nothing when [fallback] is null too.
 */
@Composable
@RemoteComposable
fun RemoteMdiIcon(
  name: String?,
  contentDescription: RemoteString?,
  tint: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  fallback: MdiIcon? = null,
) {
  val icon = MdiIcons.nameOf(name)?.let(MdiIcons::icon) ?: fallback ?: return
  RemoteMdiIcon(icon, contentDescription, tint, modifier)
}
