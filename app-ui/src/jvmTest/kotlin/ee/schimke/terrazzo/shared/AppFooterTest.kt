package ee.schimke.terrazzo.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppFooterTest {
  @Test
  fun footerCarriesNavigationStyleAndFullScreen() = runComposeUiTest {
    var style by mutableStateOf("classic")
    var settings by mutableStateOf(false)
    var fullScreen by mutableStateOf(false)
    setContent {
      AppChromeTheme(style, dark = false, host = null) {
        Text("page:${MaterialTheme.colorScheme.surface.toArgb()}")
        AppFooter(
          dashboardsSelected = !settings,
          onDashboards = { settings = false },
          onSettings = { settings = true },
          cardStyle = style,
          onCardStyle = { style = it },
          isFullScreen = fullScreen,
          onToggleFullScreen = { fullScreen = !fullScreen },
        )
      }
    }
    // Classic draws the chrome in Home Assistant's default page color, not Material's lavender.
    onNodeWithText("page:${0xFFFAFAFA.toInt()}").assertExists()
    val settingsTab =
      onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText("Settings")
      )
    settingsTab.performClick()
    assertEquals(true, settings)

    onNodeWithContentDescription("Card style: Classic").performClick()
    onNodeWithText("Lcd").performClick()
    assertEquals("lcd", style)
    onNodeWithContentDescription("Card style: Lcd").assertExists()
    // The chrome follows the card style: the LCD page sits between its card and outline greens.
    onNodeWithText("page:${0xFFFAFAFA.toInt()}").assertDoesNotExist()

    onNodeWithContentDescription("Full screen").performClick()
    assertEquals(true, fullScreen)
    onNodeWithContentDescription("Exit full screen").assertExists()
  }
}
