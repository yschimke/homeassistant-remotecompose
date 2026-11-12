package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.TextDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureAction
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureDefaults
import androidx.wear.compose.material3.onehandedgesture.OneHandedGesturePriority
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureScrollIndicator
import androidx.wear.compose.material3.onehandedgesture.OneHandedGestureScrollIndicatorState
import androidx.wear.compose.material3.onehandedgesture.rememberOneHandedGestureConfiguration
import kotlinx.coroutines.launch

/**
 * A scrolling Wear screen: [content] in a [TransformingLazyColumn] inside a [ScreenScaffold] that
 * owns its [state], so the app's `TimeText` scrolls away with the list and the scroll indicator
 * tracks it. Each item declares its Material 3 `minimumVerticalContentPadding` and
 * [TransformationSpec], keeping headers, buttons and cards clear of the time text and the round
 * screen's curve both initially and while scrolling.
 *
 * With [gestureScroll], the watch's primary one-handed gesture (a double pinch on a Pixel Watch)
 * brings the next item into view, and the scroll indicator hints at it. For lists of glanceable
 * cards only: the gesture names one target per screen, so no item of such a list takes it.
 */
@Composable
internal fun WearListScreen(
  modifier: Modifier = Modifier,
  state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
  spacing: Dp = 4.dp,
  gestureScroll: Boolean = false,
  content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
  val transformationSpec = rememberTransformationSpec()
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
    TransformingLazyColumn(
      state = state,
      modifier = modifier.fillMaxSize().then(if (gestureScroll) gestureModifier else Modifier),
      verticalArrangement = Arrangement.spacedBy(spacing),
      contentPadding = scaffoldPadding,
    ) {
      content(transformationSpec)
    }
  }
}

@Composable
internal fun TransformingLazyColumnItemScope.wearHeaderModifier(
  spec: TransformationSpec,
  modifier: Modifier = Modifier,
): Modifier =
  modifier
    .minimumVerticalContentPadding(
      top = ListHeaderDefaults.minimumTopListContentPadding,
      bottom = ListHeaderDefaults.minimumBottomListContentPadding,
    )
    .transformedHeight(this, spec)

@Composable
internal fun TransformingLazyColumnItemScope.wearCardModifier(
  spec: TransformationSpec,
  modifier: Modifier = Modifier,
): Modifier =
  modifier
    .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
    .transformedHeight(this, spec)
    .graphicsLayer { with(spec) { applyContainerTransformation(scrollProgress) } }

@Composable
internal fun TransformingLazyColumnItemScope.wearButtonModifier(
  spec: TransformationSpec,
  modifier: Modifier = Modifier,
): Modifier =
  modifier
    .fillMaxWidth()
    .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)
    .transformedHeight(this, spec)

@Composable
internal fun TransformingLazyColumnItemScope.wearTextModifier(
  spec: TransformationSpec,
  modifier: Modifier = Modifier,
): Modifier =
  modifier
    .minimumVerticalContentPadding(
      top = TextDefaults.minimumTopListContentPadding,
      bottom = TextDefaults.minimumBottomListContentPadding,
    )
    .transformedHeight(this, spec)
    .graphicsLayer { with(spec) { applyContentTransformation(scrollProgress) } }
