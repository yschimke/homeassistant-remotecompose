package ee.schimke.terrazzo.shared

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ConnectionScreenTest {
  @Test
  fun browserSignInIsPrimaryAndTokenFormIsAdvanced() = runComposeUiTest {
    var server: String? = null
    var connected: Pair<String, String>? = null
    setContent {
      AppChromeTheme("classic", dark = false, host = null) {
        ConnectionScreen(false, null, { server = it }) { url, token -> connected = url to token }
      }
    }
    onNodeWithText("Long-lived access token").assertDoesNotExist()
    onNodeWithText("Sign in with Home Assistant").assertIsNotEnabled()
    onNodeWithText("Home Assistant URL").performTextInput("https://ha.test/")
    onNodeWithText("Sign in with Home Assistant").performClick()
    assertEquals("https://ha.test", server)
    onNodeWithText("Advanced options").performClick()
    onNodeWithText("Long-lived access token").performTextInput("manual-token")
    onNodeWithText("Connect").performClick()
    assertEquals("https://ha.test/" to "manual-token", connected)
    onNodeWithText("Hide advanced options").performClick()
    onNodeWithText("Long-lived access token").assertDoesNotExist()
  }

  @Test
  fun browserErrorsAndCancellationAreVisibleWithoutOpeningAdvanced() = runComposeUiTest {
    var cancelled = false
    setContent {
      ConnectionScreen(true, "Sign-in failed", {}, true, { cancelled = true }) { _, _ -> }
    }
    onNodeWithText("Sign-in failed").assertExists()
    onNodeWithText("Waiting for browser…").assertIsNotEnabled()
    onNodeWithText("Advanced options").assertIsNotEnabled()
    onNodeWithText("Cancel sign-in").performClick()
    assertEquals(true, cancelled)
  }

  @Test
  fun hostsWithoutBrowserSignInRetainTokenSupport() = runComposeUiTest {
    setContent { ConnectionScreen(false, null, null) { _, _ -> } }
    onNodeWithText("Long-lived access token").assertExists()
    onNodeWithText("Advanced options").assertDoesNotExist()
  }
}
