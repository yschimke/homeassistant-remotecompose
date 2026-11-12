package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.action.RemoteAction
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.RemoteRole
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.contentDescription
import ee.schimke.composeai.rccreation.compose.modifier.role
import ee.schimke.composeai.rccreation.compose.modifier.semantics
import ee.schimke.composeai.rccreation.compose.state.RemoteString

/**
 * A card's tap target: [action] on click, announced as a button. Every tappable part of a card goes
 * through this rather than `clickable`, which on its own writes the click and no semantics: a
 * screen reader then finds a target with no role, and no name unless a child happens to carry one.
 * (AndroidX's `clickable` defaulted to the button role up to alpha23; androidx-main and rc-players
 * default to none.)
 *
 * [label] is what the target is called where nothing inside it says so — an icon-only button, a
 * picture. Leave it null where the target's own text names it, and mark the content it replaces
 * decorative so it isn't read twice.
 */
fun RemoteModifier.haClickable(action: RemoteAction, label: RemoteString? = null): RemoteModifier =
  clickable(action).semantics {
    role = RemoteRole.Button
    if (label != null) contentDescription = label
  }
