package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppHeaderTest {
  @Test
  fun headerCarriesSettingsAndFullScreen() = runComposeUiTest {
    var settings by mutableStateOf(false)
    var fullScreen by mutableStateOf(false)
    setContent {
      Row {
        HeaderActions(
          onSettings = { settings = true },
          isFullScreen = fullScreen,
          onToggleFullScreen = { fullScreen = !fullScreen },
        )
      }
    }
    onNodeWithContentDescription("Settings").performClick()
    assertEquals(true, settings)
    onNodeWithContentDescription("Full screen").performClick()
    assertEquals(true, fullScreen)
    onNodeWithContentDescription("Exit full screen").assertExists()
  }

  @Test
  fun aHostWithoutFullScreenShowsOnlySettings() = runComposeUiTest {
    setContent { Row { HeaderActions(onSettings = {}, isFullScreen = false, null) } }
    onNodeWithContentDescription("Settings").assertExists()
    onNodeWithContentDescription("Full screen").assertDoesNotExist()
  }

  @Test
  fun cardStyleIsChosenInSettings() = runComposeUiTest {
    var style by mutableStateOf("classic")
    setContent {
      AppChromeTheme(style, dark = false, host = null) {
        Text("page:${MaterialTheme.colorScheme.surface.toArgb()}")
        CardStyleSetting(style) { style = it }
      }
    }
    // Classic draws the chrome in Home Assistant's default page color, not Material's lavender.
    onNodeWithText("page:${0xFFFAFAFA.toInt()}").assertExists()
    onNodeWithContentDescription("Card style: Classic").performClick()
    onNodeWithText("Lcd").performClick()
    assertEquals("lcd", style)
    onNodeWithContentDescription("Card style: Lcd").assertExists()
    // The chrome follows the card style: the LCD page sits between its card and outline greens.
    onNodeWithText("page:${0xFFFAFAFA.toInt()}").assertDoesNotExist()
  }

  @Test
  fun aChosenCardStyleIsKeptAcrossLaunches() {
    val file = Files.createTempDirectory("terrazzo").resolve("settings.properties").toFile()
    assertEquals("classic", storedCardStyle(FileAppStorage(file)))
    FileAppStorage(file).set(CARD_STYLE_KEY, "braun")
    assertEquals("braun", storedCardStyle(FileAppStorage(file)))
    // A style no longer offered falls back rather than drawing in an unknown look.
    FileAppStorage(file).set(CARD_STYLE_KEY, "retired")
    assertEquals("classic", storedCardStyle(FileAppStorage(file)))
    assertEquals("classic", storedCardStyle(null))
  }
}
