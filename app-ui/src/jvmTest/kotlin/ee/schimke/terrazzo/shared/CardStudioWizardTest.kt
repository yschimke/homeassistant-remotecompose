package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.CardAgentProvider
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject

@OptIn(ExperimentalTestApi::class)
class CardStudioWizardTest {
  private val card = CardConfig("tile", buildJsonObject {})

  private fun ComposeUiTest.studio(
    token: String = "",
    defaults: CardStudioDefaults = CardStudioDefaults(),
    onSignIn: (() -> Unit)? = {},
    onDefaultsChange: (CardStudioDefaults) -> Unit = {},
  ) = setContent {
    MaterialTheme {
      Box(Modifier.size(400.dp, 900.dp)) {
        CardStudioScreen(
          card,
          HaSnapshot(),
          {},
          designToken = token,
          onSignIn = onSignIn,
          defaults = defaults,
          onDefaultsChange = onDefaultsChange,
          openEditorInBrowser = true,
          onPublished = {},
        ) {
          Text("Current card")
        }
      }
    }
  }

  /** Saves the screen for review when `STUDIO_SCREENSHOTS` names a directory. */
  private fun ComposeUiTest.capture(name: String) {
    val dir = System.getenv("STUDIO_SCREENSHOTS") ?: return
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test
  fun signingInComesFirst() = runComposeUiTest {
    var signIns = 0
    studio(onSignIn = { signIns++ })
    capture("1-signed-out")
    onNodeWithText("Sign in with GitHub").performClick()
    assertEquals(1, signIns)
    // Later steps show only their titles until then.
    onNodeWithText("How do you want to design it?").assertExists()
    onNodeWithText("Myself, in UI Builder").assertDoesNotExist()
  }

  @Test
  fun aFirstChoiceIsRememberedAndFolds() = runComposeUiTest {
    var saved: CardStudioDefaults? = null
    studio(token = "device", onDefaultsChange = { saved = it })
    capture("2-choose")
    onNodeWithText("Myself, in UI Builder").performClick()
    assertEquals(CardStudioMode.Builder, saved?.mode)
    onNodeWithText("Open in UI Builder").assertExists()
    onNodeWithText("With an AI agent").assertDoesNotExist()
    capture("3-builder")
  }

  @Test
  fun rememberedAgentSetupFoldsToOneLine() = runComposeUiTest {
    studio(
      token = "device",
      defaults =
        CardStudioDefaults(CardStudioMode.Agent, CardAgentProvider.Claude, model = "claude-x"),
    )
    onNodeWithText("Claude · claude-x").assertExists()
    onNodeWithText("Model ID").assertDoesNotExist()
    // The key isn't remembered on disk, so it is the one field asked for.
    onNodeWithText("Claude API key").assertExists()
    capture("4-agent-remembered")
  }

  @Test
  fun defaultsRoundTrip() {
    val defaults =
      CardStudioDefaults(CardStudioMode.Agent, CardAgentProvider.Agent, "m", "https://a.example")
    assertEquals(defaults, CardStudioDefaults.decode(defaults.encode()))
    assertEquals(CardStudioDefaults(), CardStudioDefaults.decode(""))
  }
}
