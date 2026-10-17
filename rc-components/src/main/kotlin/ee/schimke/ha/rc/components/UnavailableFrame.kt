@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.alpha
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.visibility
import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

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
  val labelWeight: FontWeight = FontWeight.Medium,
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
        isAvailable?.select(1f.rf, style.contentAlpha.rf) ?: style.contentAlpha.rf
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
  val theme = haTheme()
  val ink = haInk(theme.primaryText)
  val plate =
    RemoteModifier.clip(haControlShape(8))
      .background(theme.cardBackground.copy(alpha = style.plateAlpha.rf))
      .padding(horizontal = 12.rdp, vertical = 4.rdp)
  RemoteBox(modifier = modifier.then(plate)) {
    RemoteText(
      text = style.label.rs,
      color = ink.copy(alpha = ink.alpha * style.labelAlpha.rf),
      fontSize = HaType.Label,
      fontWeight = style.labelWeight,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

// RemoteCompose component visibility (`Component.Visibility`).
private val Visible = 1.ri
private val Gone = 0.ri
