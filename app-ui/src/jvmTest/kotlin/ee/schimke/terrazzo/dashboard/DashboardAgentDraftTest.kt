package ee.schimke.terrazzo.dashboard

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import ee.schimke.ha.client.dashboard.AgentEditSession
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.StartedAgentEdit
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalTestApi::class)
class DashboardAgentDraftTest {
  private val draftConfig =
    Json.parseToJsonElement(
        """{"views":[{"type":"sections","sections":[{"type":"grid",
          "cards":[{"type":"tile","entity":"cover.garage","name":"Garage Door"}]}]}]}"""
      )
      .jsonObject

  /** An open session, as if started on another device; [current] is what Home Assistant holds. */
  private class OpenSession(session: AgentEditSession?) : AgentEdits {
    val current = MutableStateFlow(session)
    val calls = mutableListOf<String>()

    override suspend fun start(urlPath: String?, request: String) = error("not started here")

    override suspend fun open(urlPath: String?) =
      listOfNotNull(current.value?.takeIf { it.isOpen && it.urlPath == urlPath })

    override suspend fun get(sessionId: String) = current.value!!

    override suspend fun resume(sessionId: String) = StartedAgentEdit(current.value!!, "")

    override fun watch(sessionId: String): Flow<AgentEditSession> = current.filterNotNull()

    override suspend fun note(sessionId: String, text: String, target: String?): AgentEditSession {
      calls += "note:$text" + (target?.let { "@$it" } ?: "")
      return current.value!!
    }

    override suspend fun discard(sessionId: String) = error("unused")

    override suspend fun apply(sessionId: String, draftId: String): AgentEditSession {
      calls += "apply:$draftId"
      return current.value!!
        .copy(status = AgentEditSession.APPLIED, applied = AgentEditSession.Applied(draftId))
        .also { current.value = it }
    }

    override suspend fun undo(sessionId: String) = error("unused")
  }

  private fun ComposeUiTest.show(edits: OpenSession, opened: MutableList<String>) = setContent {
    DashboardAgentDraft(
      sources = listOf(edits),
      urlPath = "security",
      snapshot = HaSnapshot(),
      onOpenSession = { opened += it },
      onApplied = {},
      renderCard = { _, card -> Text("draft:${card.raw["entity"]}") },
    ) {
      Text("the dashboard as it is")
    }
  }

  @Test
  fun withNoOpenSessionItIsJustTheDashboard() = runComposeUiTest {
    show(OpenSession(null), mutableListOf())
    onNodeWithText("the dashboard as it is").assertExists()
    onNodeWithText("Use it").assertDoesNotExist()
  }

  @Test
  fun aSessionFromAnotherDeviceDrawsItsDraftInPlaceAndTakesComments() = runComposeUiTest {
    val edits =
      OpenSession(
        AgentEditSession(
          id = "s1",
          urlPath = "security",
          request = "Adopt the garage door design",
          drafts = listOf(AgentEditSession.Draft("d1", "Garage tile", config = draftConfig)),
        )
      )
    val opened = mutableListOf<String>()
    show(edits, opened)
    waitUntil { onAllNodesWithText("Use it").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Agent draft: Garage tile").assertExists()
    onNodeWithText("draft:\"cover.garage\"").assertExists()
    onNodeWithText("the dashboard as it is").assertDoesNotExist()

    onNodeWithText("Show current").performClick()
    onNodeWithText("the dashboard as it is").assertExists()
    onNodeWithText("Show draft").performClick()

    // A comment pinned to the card it is about.
    onNodeWithText("Comment").performClick()
    onNodeWithText("Message your agent").performTextInput("Make it taller")
    onNodeWithText("Send").performClick()
    waitUntil { edits.calls.isNotEmpty() }
    assertEquals(
      "note:Make it taller@Garage Door (view 1, section 1, card 1)",
      edits.calls.single(),
    )

    onNodeWithText("Details").performClick()
    assertEquals(listOf("s1"), opened)

    onNodeWithText("Use it").performClick()
    waitUntil {
      onAllNodesWithText("Your dashboard uses the agent's draft.")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    assertEquals("apply:d1", edits.calls.last())
    onNodeWithText("the dashboard as it is").assertExists()
  }
}
