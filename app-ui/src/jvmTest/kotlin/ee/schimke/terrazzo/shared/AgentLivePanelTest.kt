package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.agent.AgentPresence
import ee.schimke.ha.client.agent.AgentRelayMessage
import ee.schimke.ha.client.agent.AgentRelayState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AgentLivePanelTest {
  private val waiting =
    AgentRelayState(
      version = 1,
      kind = "dashboard",
      title = "Tidy the security view",
      joinCode = "K7QM-4MD2",
      joinCodeExpiresInSeconds = 900,
    )

  private val talking =
    waiting.copy(
      version = 5,
      revision = 1,
      joinCode = null,
      agent = AgentPresence(true, true, "Claude", "Drafting a compact layout", 3),
      messages =
        listOf(
          AgentRelayMessage(1, "agent", "summary", "Moved the lock to the top"),
          AgentRelayMessage(
            2,
            "person",
            "comment",
            "Make the camera bigger",
            "Front door (card 2)",
          ),
          AgentRelayMessage(3, "agent", "message", "Full width, or two columns?"),
        ),
    )

  private fun ComposeUiTest.capture(name: String) {
    val dir = System.getenv("STUDIO_SCREENSHOTS") ?: return
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test
  fun aCodeLetsTheAgentInAndTheConversationFlows() = runComposeUiTest {
    var state by mutableStateOf(waiting)
    val copied = mutableListOf<String>()
    val claimed = mutableListOf<String>()
    val sent = mutableListOf<String>()
    var target by mutableStateOf<String?>("Front door (card 2)")
    setContent {
      MaterialTheme {
        Surface {
          Box(Modifier.width(420.dp).padding(8.dp)) {
            AgentLivePanel(
              state = state,
              busy = false,
              onCopyInvite = { copied += it },
              onNewCode = {},
              onClaim = { claimed += it },
              onSend = { sent += it },
              target = target,
              onClearTarget = { target = null },
            )
          }
        }
      }
    }
    onNodeWithText("Waiting for an agent to join").assertExists()
    onNodeWithText("K7QM-4MD2").assertExists()
    capture("agent-live-waiting")
    onNodeWithText("Copy invite for the agent").performClick()
    assertEquals(listOf("K7QM-4MD2"), copied)
    // Nothing to say to an agent that isn't there yet.
    onNodeWithText("Tell the agent what to change").assertDoesNotExist()

    // The other direction: the agent asked first and showed a code.
    onNodeWithText("Have a code from your agent?").performClick()
    onNodeWithText("Code from your agent").performTextInput("abcd-efgh")
    onNodeWithText("Connect").performClick()
    assertEquals(listOf("abcd-efgh"), claimed)

    state = talking
    waitForIdle()
    onNodeWithText("Claude · Drafting a compact layout").assertExists()
    onNodeWithText("K7QM-4MD2").assertDoesNotExist()
    onNodeWithText("Full width, or two columns?").assertExists()
    onNodeWithText("You · about Front door (card 2)").assertExists()
    onNodeWithText("About Front door (card 2)  ✕").assertExists()
    capture("agent-live-talking")
    onNodeWithText("Tell the agent what to change").performTextInput("Full width")
    onNodeWithText("Send").performClick()
    assertEquals(listOf("Full width"), sent)
    onNodeWithText("About Front door (card 2)  ✕").performClick()
    assertEquals(null, target)
  }
}
