package ee.schimke.terrazzo.shared

import androidx.compose.ui.test.*
import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.client.dashboard.DashboardChatReply
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.Section
import ee.schimke.ha.model.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalTestApi::class)
class DashboardChatTest {
  private fun card(json: String) =
    Json.parseToJsonElement(json).jsonObject.let { CardConfig(it["type"].toString().trim('"'), it) }

  private val view =
    View(
      title = "Kitchen",
      cards = listOf(card("""{"type":"tile","entity":"light.kitchen"}""")),
      sections =
        listOf(
          Section(
            cards =
              listOf(
                card(
                  """{"type":"entities","entities":["sensor.temp","light.kitchen","sensor.gone"]}""",
                ),
              ),
          ),
        ),
    )
  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "light.kitchen" to EntityState("light.kitchen", "on"),
          "sensor.temp" to EntityState("sensor.temp", "21"),
        ),
    )

  @Test
  fun theViewsEntitiesAreNamedOnceInScreenOrderWhenHomeAssistantHasThem() {
    assertEquals(listOf("light.kitchen", "sensor.temp"), viewEntityIds(view, snapshot))
    assertEquals(
      ChatScreen("Kitchen", listOf("light.kitchen", "sensor.temp")),
      ChatScreen.of(view, snapshot),
    )
    assertEquals(ChatScreen(), ChatScreen.of(null, snapshot))
  }

  @Test
  fun aTurnCarriesTheConversationAndTheDraftsSessionOn() = runComposeUiTest {
    val asked = mutableListOf<List<Any?>>()
    val drafts = mutableListOf<String>()
    val state = DashboardChatState { text, viewTitle, entityIds, conversationId, sessionId ->
      asked += listOf(text, viewTitle, entityIds, conversationId, sessionId)
      if (asked.size == 1) DashboardChatReply("Turned it off.", conversationId = "c1")
      else DashboardChatReply("Drafted.", conversationId = "c1", sessionId = "s1", drafted = true)
    }
    val agent = DashboardAgent(enabled = true, name = "Ollama", chat = true, assist = true)
    setContent {
      DashboardChat(
        agent,
        state,
        screen = { ChatScreen.of(view, snapshot) },
        onDraft = { drafts += it },
      )
    }
    onNodeWithText("Ask Ollama").performClick()
    onNodeWithText("Ask Ollama to switch what you see here, or to change this dashboard.")
      .assertExists()
    onNodeWithText("Message").performTextInput("Turn the light off")
    onNodeWithContentDescription("Send").performClick()
    waitUntil { onAllNodesWithText("Turned it off.").fetchSemanticsNodes().isNotEmpty() }
    assertEquals(
      listOf("Turn the light off", "Kitchen", listOf("light.kitchen", "sensor.temp"), null, null),
      asked.single(),
    )
    onNodeWithText("Message").performTextInput("Make it bigger")
    onNodeWithContentDescription("Send").performClick()
    waitUntil { drafts.isNotEmpty() }
    assertEquals("c1", asked[1][3])
    assertEquals(listOf("s1"), drafts)
    assertEquals("s1", state.sessionId)
    onNodeWithText("Drafted.").assertExists()
  }

  @Test
  fun aFailureIsSaidInTheChat() = runComposeUiTest {
    val state = DashboardChatState { _, _, _, _, _ -> error("offline") }
    setContent {
      DashboardChat(
        DashboardAgent(enabled = true, chat = true, assist = false),
        state,
        screen = { ChatScreen() },
        onDraft = {},
      )
    }
    onNodeWithText("Ask Your agent").performClick()
    onNodeWithText("turn on Assist", substring = true).assertExists()
    onNodeWithText("Message").performTextInput("Hi")
    onNodeWithContentDescription("Send").performClick()
    waitUntil { state.lines.size == 2 }
    assertTrue(state.lines.last().error)
    onNodeWithText("Couldn't reach Home Assistant: offline").assertExists()
  }
}
