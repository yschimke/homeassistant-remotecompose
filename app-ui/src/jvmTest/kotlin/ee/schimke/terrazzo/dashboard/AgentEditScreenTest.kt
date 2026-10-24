package ee.schimke.terrazzo.dashboard

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import ee.schimke.ha.client.HaCommandException
import ee.schimke.ha.client.dashboard.AgentEditSession
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.DashboardConflictException
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
class AgentEditScreenTest {

  private val draftConfig =
    Json.parseToJsonElement(
        """{"views":[{"type":"sections","sections":[{"type":"grid",
          "cards":[{"type":"tile","entity":"switch.pump"}]}]}]}"""
      )
      .jsonObject

  /** One session in memory; the test publishes its changes as the integration would. */
  private class FakeEdits : AgentEdits {
    val current = MutableStateFlow<AgentEditSession?>(null)
    val calls = mutableListOf<String>()
    var applyFailure: Exception? = null

    override suspend fun start(urlPath: String?, request: String): StartedAgentEdit {
      calls += "start:$urlPath:$request"
      val session =
        current.value ?: AgentEditSession(id = "s1", urlPath = urlPath, request = request)
      current.value = session
      return StartedAgentEdit(session, "Connect to /api/terrazzo/mcp, session s1")
    }

    override suspend fun open(urlPath: String?) = emptyList<AgentEditSession>()

    override suspend fun get(sessionId: String) = current.value!!

    override suspend fun resume(sessionId: String) = StartedAgentEdit(current.value!!, "")

    override fun watch(sessionId: String): Flow<AgentEditSession> = current.filterNotNull()

    override suspend fun note(sessionId: String, text: String): AgentEditSession {
      calls += "note:$text"
      return current.value!!.copy(notes = listOf(AgentEditSession.Note(text))).also {
        current.value = it
      }
    }

    override suspend fun discard(sessionId: String): AgentEditSession {
      calls += "discard"
      return current.value!!.copy(status = AgentEditSession.DISCARDED).also { current.value = it }
    }

    override suspend fun apply(sessionId: String, draftId: String): AgentEditSession {
      calls += "apply:$draftId"
      applyFailure?.let { throw it }
      return current.value!!
        .copy(status = AgentEditSession.APPLIED, applied = AgentEditSession.Applied(draftId))
        .also { current.value = it }
    }

    override suspend fun undo(sessionId: String): AgentEditSession {
      calls += "undo"
      return current.value!!.copy(status = AgentEditSession.OPEN, applied = null).also {
        current.value = it
      }
    }
  }

  private fun ComposeUiTest.show(edits: FakeEdits, shared: MutableList<String>) = setContent {
    AgentEditScreen(
      edits = edits,
      urlPath = "garden",
      title = "Garden",
      snapshot = HaSnapshot(),
      onBack = {},
      onSharePrompt = { shared += it },
      onApplied = {},
    ) { _, card ->
      Text("card:${card.type}:${card.raw["entity"]}")
    }
  }

  @Test
  fun aRequestStartsASessionWhoseDraftIsPreviewedAndApplied() = runComposeUiTest {
    val edits = FakeEdits()
    val shared = mutableListOf<String>()
    show(edits, shared)

    onNodeWithText("Request").performTextInput("Add the pump")
    onNodeWithText("Start edit session").performClick()
    waitUntil { edits.calls.isNotEmpty() }
    assertEquals("start:garden:Add the pump", edits.calls.single())
    onNodeWithText("Waiting for the agent's first draft…").assertExists()
    onNodeWithText("Share prompt").performClick()
    assertEquals(listOf("Connect to /api/terrazzo/mcp, session s1"), shared)

    // The agent submits a draft: it is drawn with the dashboard's own card renderer.
    edits.current.value =
      edits.current.value!!.copy(
        drafts = listOf(AgentEditSession.Draft("d1", "Pump tile", config = draftConfig))
      )
    waitUntil { onAllNodesWithText("Pump tile").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("card:tile:\"switch.pump\"").assertExists()
    onNodeWithText("Share prompt").assertDoesNotExist()

    onNodeWithText("Apply").performClick()
    waitUntil {
      onAllNodesWithText("Applied to Home Assistant.").fetchSemanticsNodes().isNotEmpty()
    }
    assertEquals("apply:d1", edits.calls.last())
    onNodeWithText("Undo").performClick()
    waitUntil { edits.calls.last() == "undo" }
  }

  @Test
  fun aRefusedApplySaysWhy() = runComposeUiTest {
    val edits = FakeEdits()
    edits.current.value =
      AgentEditSession(
        id = "s1",
        request = "Add the pump",
        drafts = listOf(AgentEditSession.Draft("d1", "Pump tile", config = draftConfig)),
      )
    edits.applyFailure = DashboardConflictException("This dashboard changed in Home Assistant.")
    show(edits, mutableListOf())
    onNodeWithText("Request").performTextInput("ignored")
    onNodeWithText("Start edit session").performClick()
    waitUntil { onAllNodesWithText("Apply").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Apply").performClick()
    waitUntil {
      onAllNodesWithText("This dashboard changed in Home Assistant.")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
  }

  @Test
  fun refusalsReadAsWhatThePersonCanDo() {
    assertEquals(
      "Only a Home Assistant administrator can apply or undo a draft.",
      agentEditFailure(HaCommandException("terrazzo/edit/apply", "unauthorized", "Unauthorized")),
    )
    assertEquals(
      "Update the Terrazzo integration in HACS to ask an agent.",
      agentEditFailure(HaCommandException("terrazzo/edit/start", "unknown_command", "Unknown")),
    )
  }

  @Test
  fun aDraftIsDrawnAsADashboard() {
    assertEquals(null, previewDashboard(null))
    assertEquals(1, previewDashboard(draftConfig)?.views?.size)
  }
}
