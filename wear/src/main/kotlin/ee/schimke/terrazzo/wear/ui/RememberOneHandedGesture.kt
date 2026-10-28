package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureConfiguration
import androidx.wear.compose.material3.onehandedgesture.oneHandedGesture

/**
 * [Modifier.oneHandedGesture] that survives recomposition: the same modifier instance until the
 * target itself changes ([gestureConfiguration], [onGestureLabel], [interactionSource]), with the
 * latest callbacks read through it.
 *
 * `oneHandedGesture` wraps `onGesture` in a new lambda on every call, so a fresh modifier never
 * equals the last one. Each recomposition then updates the gesture node, which re-registers the
 * target with the gesture manager and re-evaluates the hint: a second later the system is asked
 * again whether to show it, and `onGestureAvailable` replays the hint animation. The pinned list, a
 * section and the full-screen card recompose on every live entity value, so the hint played over
 * and over. Remembering the modifier keeps the node untouched, so the hint shows when the target
 * appears, as the system's own schedule allows, and not on every update.
 */
@Composable
internal fun rememberOneHandedGesture(
  gestureConfiguration: OneHandedGestureConfiguration,
  onGestureLabel: String?,
  interactionSource: MutableInteractionSource? = null,
  onGestureAvailable: () -> Unit,
  onGesture: suspend () -> Unit,
): Modifier {
  val currentOnGestureAvailable by rememberUpdatedState(onGestureAvailable)
  val currentOnGesture by rememberUpdatedState(onGesture)
  return remember(gestureConfiguration, onGestureLabel, interactionSource) {
    Modifier.oneHandedGesture(
      gestureConfiguration = gestureConfiguration,
      onGestureLabel = onGestureLabel,
      interactionSource = interactionSource,
      onGestureAvailable = { currentOnGestureAvailable() },
      onGesture = { currentOnGesture() },
    )
  }
}
