package ee.schimke.adaptivepilot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DashboardBrowserTest {
  @Test
  fun selectionSurvivesResizeAndBackReturnsToList() =
    runDesktopComposeUiTest(width = 1000, height = 800) {
      var opened: String? = null
      val width = mutableStateOf(412.dp)
      setContent { TestWindow(width.value) { DashboardBrowser(onOpen = { opened = it }) } }
      onNodeWithTag("dashboards-list").assertIsDisplayed()
      onNodeWithTag("dashboards-detail").assertDoesNotExist()
      onNodeWithTag("entry-1").performClick()
      onNodeWithTag("dashboards-detail").assertIsDisplayed()
      onNodeWithText("Energy").assertIsDisplayed()
      onNodeWithTag("dashboards-list").assertDoesNotExist()
      runOnIdle { width.value = 840.dp }
      onNodeWithTag("dashboards-list").assertIsDisplayed()
      onNodeWithTag("dashboards-detail").assertIsDisplayed()
      runOnIdle { width.value = 412.dp }
      onNodeWithText("Energy").assertIsDisplayed()
      onNodeWithTag("open-entry").performClick()
      runOnIdle { assertEquals("energy", opened) }
      onNodeWithTag("back-to-list").performClick()
      onNodeWithTag("dashboards-list").assertIsDisplayed()
      onNodeWithTag("dashboards-detail").assertDoesNotExist()
    }

  @Test
  fun windowSizeDrivesAdaptationEvenWhenContentIsNarrower() =
    runDesktopComposeUiTest(width = 840, height = 800) {
      setContent {
        // A rail or parent padding can reduce content width below the window breakpoint.
        Box(Modifier.requiredSize(760.dp, 720.dp)) { DashboardBrowser() }
      }
      onNodeWithTag("dashboards-list").assertIsDisplayed()
      onNodeWithTag("dashboards-detail").assertIsDisplayed()
    }

  @Test
  fun breakpointOpensBothPanesAt840dp() =
    runDesktopComposeUiTest(width = 1000, height = 800) {
      val width = mutableStateOf(839.dp)
      setContent { TestWindow(width.value) { DashboardBrowser() } }
      onNodeWithTag("dashboards-detail").assertDoesNotExist()
      runOnIdle { width.value = 840.dp }
      onNodeWithTag("dashboards-list").assertIsDisplayed()
      onNodeWithTag("dashboards-detail").assertIsDisplayed()
    }
}

// Model a window resize, rather than deriving adaptive information from the content box.
@Composable
private fun TestWindow(width: Dp, content: @Composable () -> Unit) {
  val density = LocalDensity.current
  val hostWindow = LocalWindowInfo.current
  val window =
    object : WindowInfo by hostWindow {
      override val containerSize: IntSize =
        with(density) { IntSize(width.roundToPx(), 720.dp.roundToPx()) }
    }
  CompositionLocalProvider(LocalWindowInfo provides window) {
    Box(Modifier.requiredSize(width, 720.dp)) { content() }
  }
}
