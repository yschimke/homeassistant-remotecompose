@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteCanvas
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.RemoteColor
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------------------------
// A lock, as a Layer 2 visual: the first component written only against the theme grammar and
// slots. There is no Meters<Family>.kt drawing of it and no `when` over families — every theme,
// bespoke or kit, draws it from its grammar (GrammarMeters.kt), and a theme that wants its own
// lock overrides HaComponentSlot.Lock. A front door, a car's doors or a gate all use it.
// ---------------------------------------------------------------------------------------------

/**
 * A padlock fitted and centred in the canvas at [LockAspect], drawn in the active theme's way. The
 * shackle is seated when [locked] is 1 and lifted clear at 0, moving with the value, so pass a
 * live, animated fraction.
 *
 * @param locked how locked, `0..1` — normally a live two-state value, animated.
 * @param color the body's colour, normally live: a semantic locked / unlocked accent.
 */
@Composable
@RemoteComposable
fun RemoteHaLockGlyph(
  locked: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier = RemoteModifier,
  trackColor: RemoteColor? = null,
) {
  val look = meterLook(color, trackColor)
  val renderer = themedRenderer(HaComponentSlot.Lock) ?: GrammarRenderers.Lock
  val grammar = haGrammar()
  RemoteCanvas(modifier = modifier) { render(renderer, HaVisualSpec(look, locked, grammar)) }
}

/**
 * [RemoteHaLockGlyph] for a lock entity, live: bound to `<entityId>.is_on`, which hosts push as
 * true while a `lock.*` entity is `locked` (`HaLiveBindings`). The shackle moves by the theme's
 * `spatial` motion token on a change, and the body takes [lockedColor] or [unlockedColor] —
 * semantic, kept legible on the card surface, or the theme's ink on a monochrome theme.
 *
 * With no [entityId] (a preview) it draws [locked] as a constant.
 */
@Composable
@RemoteComposable
fun RemoteHaLiveLockGlyph(
  entityId: String?,
  locked: Boolean,
  modifier: RemoteModifier = RemoteModifier,
  lockedColor: Color = HaStatusColors.Normal,
  unlockedColor: Color = HaStatusColors.Warning,
) {
  val bound = LiveValues.isOn(entityId, locked)
  val lockedRc = haLegible(lockedColor).rc
  val unlockedRc = haLegible(unlockedColor).rc
  val fraction =
    if (bound == null) (if (locked) 1f else 0f).rf
    else animateRemoteFloat(bound.select(1f.rf, 0f.rf), haMotion().spatial)
  val color = bound?.select(lockedRc, unlockedRc) ?: (if (locked) lockedRc else unlockedRc)
  RemoteHaLockGlyph(fraction, color, modifier)
}

/**
 * One lock as a row — the glyph, then its name over its live state (`<entityId>.state`, formatted
 * by the host as it is everywhere). A car's doors, boot and charge port are a column of these; a
 * front door is one. It draws no chrome (rule 5) and owns no action: the card around it does.
 */
@Composable
@RemoteComposable
fun RemoteHaLockRow(
  entityId: String?,
  name: String,
  stateText: String,
  locked: Boolean,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  // The gap is the text's start padding, not `spacedBy`, which the player overflows by.
  RemoteRow(modifier = modifier, verticalAlignment = RemoteAlignment.CenterVertically) {
    RemoteHaLiveLockGlyph(entityId, locked, RemoteModifier.size(28.rdp, 36.rdp))
    RemoteColumn(RemoteModifier.padding(start = HaSpace.M.rdp)) {
      RemoteText(
        text = name.rs,
        color = theme.primaryText,
        fontSize = HaType.Body,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
      RemoteText(
        text = LiveValues.state(entityId, stateText),
        color = theme.secondaryText,
        fontSize = HaType.Label,
        style = haTextStyle(),
        maxLines = 1,
        overflow = RemoteTextOverflow.Ellipsis,
      )
    }
  }
}
