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
    homeServer: HomeDesignServer? = null,
    canPublish: Boolean = true,
    cloudSessionKey: String? = null,
    selectionProblem: String? = null,
    onRetrySelections: (() -> Unit)? = null,
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
          onPublished = if (canPublish) ({}) else null,
          homeServer = homeServer,
          cloudSessionKey = cloudSessionKey,
          selectionProblem = selectionProblem,
          onRetrySelections = onRetrySelections,
        ) {
          Text("Current card")
        }
      }
    }
  }

  @Test
  fun selectionFailureIsExplainedAndCanBeRetriedWithoutLeavingStudio() = runComposeUiTest {
    var retries = 0
    studio(
      onSignIn = null,
      canPublish = false,
      selectionProblem = "Couldn't load card selections (unknown_command).",
      onRetrySelections = { retries++ },
    )
    onNodeWithText("Couldn't load card selections (unknown_command).").assertExists()
    onNodeWithText("Retry loading card selections").performClick()
    assertEquals(1, retries)
    onNodeWithText("This host can create and review drafts but can't apply a custom design yet.")
      .assertDoesNotExist()
  }

  @Test
  fun restoredCloudRequestIsVisibleWithoutOfferingAnUnfetchedDraft() = runComposeUiTest {
    CardAgentSessions.clear()
    CardAgentSessions.put(
      "home:card",
      // An unreachable server: the session stays, its live panel waits to connect.
      SavedCardAgentSession(
        "https://design.invalid",
        "owner",
        "Use the door design",
        Long.MAX_VALUE,
      ),
    )
    try {
      studio(onSignIn = null, canPublish = false, cloudSessionKey = "home:card")
      // The session itself: its conversation and Stop, nothing to fill in.
      onNodeWithText("Stop").assertExists()
      onNodeWithText("What should change?").assertDoesNotExist()
      onNodeWithText("Review draft", substring = true).assertDoesNotExist()
      capture("restored-cloud-session")
    } finally {
      CardAgentSessions.clear()
    }
  }

  /**
   * The agent flow opens by starting its session, with nothing to type: it is starting, or (with no
   * design server reachable in a test) offers another try.
   */
  private fun ComposeUiTest.sessionStarts() {
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithText("Starting a session…").fetchSemanticsNodes().isNotEmpty() ||
        onAllNodesWithText("New session").fetchSemanticsNodes().isNotEmpty()
    }
  }

  /** Saves the screen for review when `STUDIO_SCREENSHOTS` names a directory. */
  private fun ComposeUiTest.capture(name: String) {
    val dir = System.getenv("STUDIO_SCREENSHOTS") ?: return
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test
  fun signingInIsOfferedForUsingADesign() = runComposeUiTest {
    var signIns = 0
    studio(onSignIn = { signIns++ })
    capture("1-signed-out")
    onNodeWithText("Sign in with GitHub").performClick()
    assertEquals(1, signIns)
    // Asking doesn't wait for it: only Use it needs the person's account.
    sessionStarts()
  }

  @Test
  fun aHomeAssistantsOwnServerNeedsNoSignIn() = runComposeUiTest {
    studio(homeServer = HomeDesignServer("https://home.example", { "ha-token" }, { null }))
    onNodeWithText("Sign in with GitHub").assertDoesNotExist()
    sessionStarts()
  }

  @Test
  fun everyoneStartsWithTheirAgent() = runComposeUiTest {
    studio(token = "device")
    capture("2-ask")
    // Opening it starts the session: there's no request to write first.
    sessionStarts()
    onNodeWithText("What should change?").assertDoesNotExist()
    // No mode to choose and no API key to enter.
    onNodeWithText("How do you want to design it?").assertDoesNotExist()
    onNodeWithText("API key", substring = true).assertDoesNotExist()
  }

  @Test
  fun uiBuilderIsUnderMoreOptionsAndRemembered() = runComposeUiTest {
    var saved: CardStudioDefaults? = null
    studio(token = "device", onDefaultsChange = { saved = it })
    onNodeWithText("Open in UI Builder").assertDoesNotExist()
    onNodeWithText("More options").performClick()
    onNodeWithText("Design it myself in UI Builder").performClick()
    assertEquals(CardStudioMode.Builder, saved?.mode)
    onNodeWithText("Open in UI Builder").assertExists()
    capture("3-builder")
    onNodeWithText("Ask your agent instead").performClick()
    assertEquals(CardStudioMode.CloudAgent, saved?.mode)
    sessionStarts()
  }

  @Test
  fun anEarlierApiKeyChoiceOpensTheAgentFlow() = runComposeUiTest {
    val old =
      CardStudioDefaults.decode("""{"mode":"Agent","provider":"Claude","model":"claude-x"}""")
    assertEquals(CardStudioMode.Agent, old.mode)
    studio(token = "device", defaults = old)
    sessionStarts()
  }

  @Test
  fun theAgentCanBeAskedWithoutAHostBackendOrDesignServerLogin() = runComposeUiTest {
    studio(onSignIn = null, canPublish = false)
    sessionStarts()
    onNodeWithText("can't apply a custom design yet", substring = true).assertExists()
  }

  @Test
  fun defaultsRoundTrip() {
    val defaults = CardStudioDefaults(CardStudioMode.Builder)
    assertEquals(defaults, CardStudioDefaults.decode(defaults.encode()))
    assertEquals(CardStudioDefaults(), CardStudioDefaults.decode(""))
  }
}
