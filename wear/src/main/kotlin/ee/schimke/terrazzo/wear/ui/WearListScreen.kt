package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureDefaults
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePriority
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureScrollIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureScrollIndicatorState
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import kotlinx.coroutines.launch

/**
 * A scrolling Wear screen: [content] in a [ScalingLazyColumn] inside a [ScreenScaffold] that owns
 * its [state], so the app's `TimeText` scrolls away with the list and the scroll indicator tracks
 * it. The scaffold's padding keeps the first and last items clear of the time text and the screen's
 * curve.
 *
 * With [gestureScroll], the watch's primary one-handed gesture (a double pinch on a Pixel Watch)
 * brings the next item into view, and the scroll indicator hints at it. For lists of glanceable
 * cards only: the gesture names one target per screen, so no item of such a list takes it.
 */
@Composable
internal fun WearListScreen(
  modifier: Modifier = Modifier,
  state: ScalingLazyListState = rememberScalingLazyListState(),
  spacing: Dp = 4.dp,
  gestureScroll: Boolean = false,
  content: ScalingLazyListScope.() -> Unit,
) {
  val gesture =
    rememberOneHandedGestureConfiguration(
      action = OneHandedGestureAction.Primary,
      priority = OneHandedGesturePriority.Scrollable,
    )
  val hint = remember(gesture) { OneHandedGestureScrollIndicatorState() }
  val scope = rememberCoroutineScope()
  val gestureModifier =
    rememberOneHandedGesture(
      gestureConfiguration = gesture,
      onGestureLabel = "Next card",
      onGestureAvailable = { scope.launch { hint.showIndicator() } },
      onGesture = { OneHandedGestureDefaults.scrollDownToNextItem(state) },
    )
  ScreenScaffold(
    scrollState = state,
    scrollIndicator = {
      if (gestureScroll) {
        OneHandedGestureScrollIndicator(gesture, hint, state, Modifier.align(Alignment.CenterEnd))
      } else {
        ScrollIndicator(state, Modifier.align(Alignment.CenterEnd))
      }
    },
  ) { scaffoldPadding ->
    ScalingLazyColumn(
      state = state,
      modifier = modifier.fillMaxWidth().then(if (gestureScroll) gestureModifier else Modifier),
      verticalArrangement = Arrangement.spacedBy(spacing),
      contentPadding =
        PaddingValues(
          start = 14.dp,
          end = 14.dp,
          top = scaffoldPadding.calculateTopPadding(),
          bottom = scaffoldPadding.calculateBottomPadding(),
        ),
      content = content,
    )
  }
}
