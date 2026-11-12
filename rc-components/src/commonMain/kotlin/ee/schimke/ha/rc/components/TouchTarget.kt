package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import ee.schimke.composeai.rccreation.compose.action.RemoteAction
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.RemoteString
import ee.schimke.composeai.rccreation.compose.state.rdp

/** The smallest tap target, in dp (Material's and the accessibility checker's 48dp). */
const val MinTouchTargetDp: Int = 48

/**
 * A tap target at least [MinTouchTargetDp] in each direction around a control drawn [widthDp] ×
 * [heightDp]: the [content] keeps its own size, centred in the target. RemoteCompose clamps a child
 * to its parent, so a tap area can't spill past a control's layout bounds; callers that must keep
 * the drawn positions pull the surrounding spacing in by the target's extra size instead. [label]
 * is what a screen reader announces for the target; the content should be decorative.
 */
@Composable
@RemoteComposable
fun RemoteHaTouchTarget(
  widthDp: Int,
  heightDp: Int,
  action: RemoteAction?,
  label: RemoteString?,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val tap = action?.let { RemoteModifier.haClickable(it, label) } ?: RemoteModifier
  RemoteBox(
    modifier =
      modifier
        .then(tap)
        .size(maxOf(widthDp, MinTouchTargetDp).rdp, maxOf(heightDp, MinTouchTargetDp).rdp),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteBox(
      modifier = RemoteModifier.size(widthDp.rdp, heightDp.rdp),
      contentAlignment = RemoteAlignment.Center,
    ) {
      content()
    }
  }
}
