@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.alpha
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.visibility
import ee.schimke.composeai.rccreation.compose.state.RemoteBoolean
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rccreation.compose.state.ri
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.text.RemoteFontWeight
import ee.schimke.composeai.rccreation.compose.text.RemoteTextOverflow

/**
 * How a card under an uber theme shows that the entity it is about is unavailable: one treatment
 * every card in the theme shares, decided by the theme rather than by each card.
 *
 * The card's content fades to [contentAlpha] and [label] is set over its middle like a stamp: in
 * the theme's type, in clear but partly transparent lettering ([labelAlpha]), on a plate of the
 * card's own surface ([plateAlpha]) so it reads over whatever text lies beneath, the plate's corner
 * from the theme's controls.
 */
data class HaUnavailableStyle(
  val label: String = "Unavailable",
  val contentAlpha: Float = 0.35f,
  val labelAlpha: Float = 0.8f,
  val plateAlpha: Float = 0.92f,
  val labelWeight: RemoteFontWeight = RemoteFontWeight.Medium,
)

/**
 * Draws [content] with the active uber theme's [HaUnavailableStyle] whenever the entity it is about
 * is unavailable.
 * - Live ([isAvailable] non-null, the in-app players): the label's visibility and the content's
 *   fade follow `<entity>.is_available`.
 * - Baked (widgets, where availability isn't bound): the choice is made at encode time from
 *   [initiallyAvailable]; an available entity's content is drawn untouched, and an unavailable
 *   one's is faded and labelled.
 *
 * [content] receives the modifier to give its root.
 */
@Composable
@RemoteComposable
fun RemoteHaUnavailableFrame(
  isAvailable: RemoteBoolean?,
  initiallyAvailable: Boolean,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable @RemoteComposable (RemoteModifier) -> Unit,
) {
  if (isAvailable == null && initiallyAvailable) {
    content(modifier)
    return
  }
  val style = currentHaUberTheme().theme.unavailable
  RemoteBox(modifier = modifier, contentAlignment = RemoteAlignment.Center) {
    val fade =
      RemoteModifier.alpha(
        isAvailable?.select(1f.rf, style.contentAlpha.rf) ?: style.contentAlpha.rf,
      )
    RemoteBox(modifier = fade.fillMaxWidth()) { content(RemoteModifier.fillMaxWidth()) }
    val shown =
      if (isAvailable == null) RemoteModifier
      else RemoteModifier.visibility(isAvailable.select(Gone, Visible))
    UnavailableLabel(style, shown)
  }
}

@Composable
@RemoteComposable
private fun UnavailableLabel(style: HaUnavailableStyle, modifier: RemoteModifier) {
  if (haLayouts().unavailable == HaUnavailableLabel.Tape) {
    ConsoleTapeLabel(style.label.rs, modifier)
    return
  }
  val theme = haTheme()
  val ink = haInk(theme.primaryText)
  val plate =
    RemoteModifier.clip(haControlShape(8))
      .background(theme.cardBackground.copy(alpha = style.plateAlpha.rf))
      // Two text lines tall, the name-over-state block a tile or row draws under it: a thinner
      // plate let the faded lines show above and below the label, which read as text overlap.
      .padding(horizontal = 12.rdp, vertical = 12.rdp)
  RemoteBox(modifier = modifier.then(plate)) {
    RemoteText(
      text = style.label.rs,
      color = ink.copy(alpha = ink.alpha * style.labelAlpha.rf),
      fontSize = HaType.Label,
      fontWeight = style.labelWeight,
      style = haTextStyle(),
      maxLines = 1,
      overflow = RemoteTextOverflow.Ellipsis,
    )
  }
}

// RemoteCompose component visibility (`Component.Visibility`).
private val Visible = 1.ri
private val Gone = 0.ri
