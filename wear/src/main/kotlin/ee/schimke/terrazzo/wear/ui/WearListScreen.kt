package ee.schimke.terrazzo.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold

/**
 * A scrolling Wear screen: [content] in a [ScalingLazyColumn] inside a [ScreenScaffold] that owns
 * its [state], so the app's `TimeText` scrolls away with the list and the scroll indicator tracks
 * it. The scaffold's padding keeps the first and last items clear of the time text and the screen's
 * curve.
 */
@Composable
internal fun WearListScreen(
  modifier: Modifier = Modifier,
  state: ScalingLazyListState = rememberScalingLazyListState(),
  spacing: Dp = 4.dp,
  content: ScalingLazyListScope.() -> Unit,
) {
  ScreenScaffold(scrollState = state) { scaffoldPadding ->
    ScalingLazyColumn(
      state = state,
      modifier = modifier.fillMaxWidth(),
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
