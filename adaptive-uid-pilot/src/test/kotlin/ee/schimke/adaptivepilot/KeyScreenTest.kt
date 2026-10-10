package ee.schimke.adaptivepilot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KeyScreenTest {
  @Test
  fun editedConnectionAddressSurvivesResizeAndReachesCallback() =
    runDesktopComposeUiTest(width = 1000, height = 800) {
      val width = mutableStateOf(412.dp)
      var connection: Pair<String, String>? = null
      setContent {
        TestWindow(width.value) {
          KeyScreen(
            "connection",
            initialDetail = true,
            unavailable = true,
            onAction = { key, value -> connection = key to value },
          )
        }
      }
      onNodeWithTag("input").performTextReplacement("https://ha.example.com")
      runOnIdle { width.value = 840.dp }
      onNodeWithTag("connection-list").assertIsDisplayed()
      onNodeWithTag("input").assertTextContains("https://ha.example.com")
      onNodeWithTag("action").performClick()
      runOnIdle { assertEquals("connect" to "https://ha.example.com", connection) }
      onNodeWithTag("input").performTextReplacement("not a URL")
      onNodeWithTag("action").assertIsNotEnabled()
    }

  @Test
  fun dashboardSelectionRetainsOtherChoicesAcrossNavigation() =
    runDesktopComposeUiTest(width = 1000, height = 800) {
      var result: Pair<String, String>? = null
      setContent {
        TestWindow(412.dp) {
          KeyScreen("choose-dashboards", onAction = { key, value -> result = key to value })
        }
      }
      onNodeWithTag("panel-0").performClick()
      onNodeWithTag("control").performClick()
      onNodeWithTag("back-to-list").performClick()
      onNodeWithTag("panel-1").performClick()
      onNodeWithTag("control").assertIsOn()
      onNodeWithTag("action").performClick()
      runOnIdle { assertEquals("save-selection" to "energy", result) }
      onNodeWithTag("back-to-list").performClick()
      onNodeWithTag("panel-0").performClick()
      onNodeWithTag("control").assertIsOff()
    }

  @Test
  fun emptySelectionCannotBeSaved() =
    runDesktopComposeUiTest(width = 840, height = 800) {
      setContent { KeyScreen("choose-dashboards", emptySelection = true) }
      onNodeWithTag("action").assertIsNotEnabled()
      onNodeWithText("Select at least one dashboard.").assertIsDisplayed()
      onNodeWithTag("control").performClick()
      onNodeWithTag("action").assertIsEnabled()
    }

  @Test
  fun offlineDashboardCannotOperateDevices() =
    runDesktopComposeUiTest(width = 840, height = 800) {
      setContent { KeyScreen("dashboard-view", unavailable = true) }
      onNodeWithTag("control").assertIsNotEnabled()
      onNodeWithText("Offline · Saved values may be out of date. Controls are disabled.")
        .assertIsDisplayed()
    }

  @Test
  fun deviceControlUsesTheSelectedEntity() =
    runDesktopComposeUiTest(width = 840, height = 800) {
      var action: Pair<String, String>? = null
      setContent {
        KeyScreen(
          "dashboard-view",
          initialDetail = true,
          initialSelectedIndex = 1,
          onAction = { key, value -> action = key to value },
        )
      }
      onNodeWithTag("control").performClick()
      runOnIdle { assertEquals("light.kitchen" to "true", action) }
    }

  @Test
  fun emptyWatchSlotCannotBeClearedAndAssignUsesSelectedSlot() =
    runDesktopComposeUiTest(width = 840, height = 800) {
      var action: Pair<String, String>? = null
      setContent {
        KeyScreen(
          "wear-widgets",
          initialDetail = true,
          initialSelectedIndex = 1,
          onAction = { key, value -> action = key to value },
        )
      }
      onNodeWithTag("action").assertIsNotEnabled()
      onNodeWithTag("assign").performClick()
      runOnIdle { assertEquals("assign-slot" to "2:light.living_room", action) }
    }

  @Test
  fun settingsCallsTheCorrectPreference() =
    runDesktopComposeUiTest(width = 840, height = 800) {
      var action: Pair<String, String>? = null
      setContent {
        KeyScreen(
          "settings",
          initialDetail = true,
          initialSelectedIndex = 1,
          onAction = { key, value -> action = key to value },
        )
      }
      onNodeWithTag("control").performClick()
      runOnIdle { assertEquals("grid-layout" to "true", action) }
    }

  @Test
  fun installedWidgetPlaceholderDoesNotInventActions() =
    runDesktopComposeUiTest(width = 840, height = 800) {
      setContent { KeyScreen("widgets") }
      onNodeWithTag("action").assertDoesNotExist()
      onNodeWithText("No widgets yet").assertIsDisplayed()
    }
}
