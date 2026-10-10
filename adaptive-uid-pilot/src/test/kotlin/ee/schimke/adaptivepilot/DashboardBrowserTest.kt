package ee.schimke.adaptivepilot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
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
      setContent {
        Box(Modifier.requiredSize(width.value, 720.dp)) {
          DashboardBrowser(onOpen = { opened = it })
        }
      }
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
  fun breakpointOpensBothPanesAt840dp() =
    runDesktopComposeUiTest(width = 1000, height = 800) {
      val width = mutableStateOf(839.dp)
      setContent { Box(Modifier.requiredSize(width.value, 720.dp)) { DashboardBrowser() } }
      onNodeWithTag("dashboards-detail").assertDoesNotExist()
      runOnIdle { width.value = 840.dp }
      onNodeWithTag("dashboards-list").assertIsDisplayed()
      onNodeWithTag("dashboards-detail").assertIsDisplayed()
    }
}
