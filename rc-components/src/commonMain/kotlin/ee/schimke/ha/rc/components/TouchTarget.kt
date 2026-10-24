package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.contentDescription
import androidx.compose.remote.creation.compose.modifier.semantics
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable

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
  action: Action?,
  label: RemoteString?,
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val tap =
    action?.let { a ->
      RemoteModifier.clickable(a).let {
        if (label != null) it.semantics { contentDescription = label } else it
      }
    } ?: RemoteModifier
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
