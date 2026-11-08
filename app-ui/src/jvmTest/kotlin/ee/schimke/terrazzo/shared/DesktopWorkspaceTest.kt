package ee.schimke.terrazzo.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.terrazzo.dashboard.DashboardListState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopWorkspaceTest {
  @Test
  fun sidebarSelectsTheDefaultDashboardAndNavigatesByPath() = runComposeUiTest {
    var selected: String? = null
    var settings = 0
    setContent {
      MaterialTheme {
        DesktopDashboardSidebar(
          dashboards,
          null,
          true,
          false,
          true,
          onDashboard = { selected = it },
          onSettings = { settings++ },
        )
      }
    }
    onNodeWithText("Overview").assertIsSelected()
    onNodeWithText("Upstairs").performClick()
    runOnIdle { assertEquals("upstairs", selected) }
    onNodeWithText("Settings").performClick()
    runOnIdle { assertEquals(1, settings) }
  }

  @Test
  fun editingDisablesSidebarActionsUntilEditingFinishes() = runComposeUiTest {
    var enabled by mutableStateOf(false)
    var navigations = 0
    setContent {
      MaterialTheme {
        DesktopDashboardSidebar(
          dashboards,
          null,
          false,
          false,
          enabled,
          onDashboard = { navigations++ },
          onSettings = { navigations++ },
        )
      }
    }
    onNodeWithText("Upstairs").assertIsNotEnabled().performClick()
    onNodeWithText("Settings").assertIsNotEnabled().performClick()
    runOnIdle {
      assertEquals(0, navigations)
      enabled = true
    }
    onNodeWithText("Upstairs").assertIsEnabled().performClick()
    runOnIdle { assertEquals(1, navigations) }
  }

  @Test
  fun clearingTheConnectionRevokesNativeCommands() {
    val commands = DesktopCommands()
    var calls = 0
    assertFalse(commands.canNavigate)
    assertFalse(commands.canEdit)
    commands.bind({ calls++ }, { calls++ }, { calls++ })
    assertTrue(commands.canNavigate)
    assertTrue(commands.canEdit)
    commands.home()
    commands.settings()
    commands.edit()
    assertEquals(3, calls)
    commands.clear()
    commands.home()
    commands.settings()
    commands.edit()
    assertFalse(commands.canNavigate)
    assertFalse(commands.canEdit)
    assertEquals(3, calls)
  }

  private val dashboards =
    DashboardListState.Ready(
      listOf(DashboardSummary(null, "Overview"), DashboardSummary("upstairs", "Upstairs")),
    )
}
