package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.HaCommandException
import ee.schimke.ha.client.agent.AgentPresence
import ee.schimke.ha.client.agent.AgentRelayState
import ee.schimke.ha.client.dashboard.AgentEditSession
import ee.schimke.ha.client.dashboard.AgentEdits
import ee.schimke.ha.client.dashboard.DashboardConflictException
import ee.schimke.ha.client.dashboard.EditScope
import ee.schimke.ha.client.dashboard.StartedAgentEdit
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
  private class FakeEdits(
    val listed: List<AgentEditSession> = emptyList(),
    /** As the relay's open(): only sessions it relays, not Home Assistant's alone. */
    val openHidesUnrelayed: Boolean = false,
  ) : AgentEdits {
    val current = MutableStateFlow<AgentEditSession?>(null)
    val calls = mutableListOf<String>()
    var applyFailure: Exception? = null
    var baseline: kotlinx.serialization.json.JsonObject? = null

    override suspend fun start(
      urlPath: String?,
      request: String,
      scope: EditScope?,
    ): StartedAgentEdit {
      calls += "start:$urlPath:$request:${scope?.label}"
      val session =
        current.value
          ?: AgentEditSession(
            id = "s1",
            urlPath = urlPath,
            request = request,
            baseline = baseline,
            scope = scope,
          )
      current.value = session
      return StartedAgentEdit(session, "")
    }

    override suspend fun open(urlPath: String?) =
      if (openHidesUnrelayed) emptyList() else listed.filter { it.isOpen }

    override suspend fun recent(urlPath: String?) = listed

    override suspend fun prompt(sessionId: String) =
      "Connect to /api/terrazzo/mcp, session $sessionId"

    override suspend fun get(sessionId: String) = current.value!!

    override suspend fun resume(sessionId: String) = StartedAgentEdit(current.value!!, "")

    override fun watch(sessionId: String): Flow<AgentEditSession> =
      current.filterNotNull().let { flow ->
        if (current.value == null)
          listed.firstOrNull { it.id == sessionId }?.let { current.value = it }
        flow
      }

    override suspend fun note(sessionId: String, text: String, target: String?): AgentEditSession {
      calls += "note:$text" + (target?.let { "@$it" } ?: "")
      return current.value!!
        .copy(notes = listOf(AgentEditSession.Note(text, target = target)))
        .also { current.value = it }
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

  private fun ComposeUiTest.show(
    edits: FakeEdits,
    shared: MutableList<String>,
    writeBlockedReason: String? = null,
    cloudServer: String? = null,
    startScope: EditScope? = null,
  ) = setContent {
    AgentEditScreen(
      edits = edits,
      urlPath = "garden",
      title = "Garden",
      snapshot = HaSnapshot(),
      onBack = {},
      onSharePrompt = { shared += it },
      onApplied = {},
      writeBlockedReason = writeBlockedReason,
      cloudServer = cloudServer,
      startScope = startScope,
    ) { _, card ->
      Text("card:${card.type}:${card.raw["entity"]}")
    }
  }

  @Test
  fun aNewSessionNeedsNothingTypedAndPreviewsTheDashboardAsItIs() = runComposeUiTest {
    val edits = FakeEdits().apply { baseline = draftConfig }
    val shared = mutableListOf<String>()
    show(edits, shared)
    onNodeWithText("No sessions on Garden yet.").assertExists()
    onNodeWithText("New session").performClick()
    waitUntil { edits.current.value != null }
    assertEquals(
      "start:garden:Work on the Garden dashboard with me. Start with any comments I've left on " +
        "the preview; if there are none yet, say hello and follow my comments as they come.:null",
      edits.calls.single(),
    )
    // Straight into the preview: the dashboard as it is, every card open to a comment.
    waitUntil { onAllNodesWithText("card:tile:\"switch.pump\"").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Comment").performClick()
    onNodeWithText("Tell the agent what to change").performTextInput("Bigger")
    onNodeWithText("Send").performClick()
    waitUntil { edits.calls.any { it.startsWith("note:") } }
    assertEquals("note:Bigger@switch.pump (view 1, section 1, card 1)", edits.calls.last())
    // One prompt, for one or more agents.
    onNodeWithText("Copy prompt").performClick()
    waitUntil { shared.isNotEmpty() }
    assertEquals(listOf("Connect to /api/terrazzo/mcp, session s1"), shared)
  }

  @Test
  fun aSectionOpensStraightIntoItsSession() = runComposeUiTest {
    val edits = FakeEdits().apply { baseline = draftConfig }
    show(edits, mutableListOf(), startScope = EditScope.section(0, 0, "Pumps"))
    waitUntil { edits.current.value != null }
    assertEquals(
      "start:garden:Work on the Pumps section of the Garden dashboard with me. Start with any " +
        "comments I've left on the preview; if there are none yet, say hello and follow my " +
        "comments as they come.:Pumps",
      edits.calls.single(),
    )
    waitUntil { onAllNodesWithText("Pumps").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("card:tile:\"switch.pump\"").assertExists()
  }

  @Test
  fun theListShowsOpenAndRecentSessions() = runComposeUiTest {
    val edits =
      FakeEdits(
        listOf(
          AgentEditSession(
            id = "open",
            scope = EditScope.section(0, 1, "Garage"),
            drafts = listOf(AgentEditSession.Draft("d1")),
          ),
          AgentEditSession(id = "done", status = AgentEditSession.APPLIED),
        )
      )
    show(edits, mutableListOf())
    waitUntil { onAllNodesWithText("Garage").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Open · 1 draft").assertExists()
    onNodeWithText("In use · no draft yet").assertExists()
    onNodeWithText("Garage").performClick()
    waitUntil { onAllNodesWithText("Copy prompt").fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun aDirectlySignedInAgentTalksLive() = runComposeUiTest {
    val edits = FakeEdits()
    show(edits, mutableListOf())
    onNodeWithText("New session").performClick()
    waitUntil { edits.current.value != null }
    edits.current.value =
      edits.current.value!!.copy(
        drafts = listOf(AgentEditSession.Draft("d1", summary = "Pump tile", config = draftConfig)),
        messages = listOf(AgentEditSession.Message("Full width?", seq = 1)),
        agent = AgentEditSession.Activity("Waiting for feedback", seenAt = 100.0),
        serverTime = 110.0,
      )
    waitUntil { onAllNodesWithText("Full width?").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Agent · Waiting for feedback").assertExists()
    onNodeWithText("Give your agent this code").assertDoesNotExist()
  }

  @Test
  fun aDraftIsPreviewedAndApplied() = runComposeUiTest {
    val edits = FakeEdits()
    show(edits, mutableListOf())
    onNodeWithText("New session").performClick()
    waitUntil { edits.current.value != null }
    // The agent submits a draft: it is drawn with the dashboard's own card renderer.
    edits.current.value =
      edits.current.value!!.copy(
        drafts = listOf(AgentEditSession.Draft("d1", "Pump tile", config = draftConfig))
      )
    waitUntil { onAllNodesWithText("Pump tile").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("card:tile:\"switch.pump\"").assertExists()
    onNodeWithText("Use it").performClick()
    waitUntil {
      onAllNodesWithText("Done: your dashboard uses it.").fetchSemanticsNodes().isNotEmpty()
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
        drafts = listOf(AgentEditSession.Draft("d1", "Pump tile", config = draftConfig)),
      )
    edits.applyFailure = DashboardConflictException("This dashboard changed in Home Assistant.")
    show(edits, mutableListOf())
    onNodeWithText("New session").performClick()
    waitUntil { onAllNodesWithText("Use it").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Use it").performClick()
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
      "Update the Terrazzo integration in the Marketplace to ask an agent.",
      agentEditFailure(HaCommandException("terrazzo/edit/start", "unknown_command", "Unknown")),
    )
  }

  @Test
  fun aDraftIsDrawnAsADashboard() {
    assertEquals(null, previewDashboard(null))
    assertEquals(1, previewDashboard(draftConfig)?.views?.size)
  }

  @Test
  fun readOnlyModeReviewsButNeverApplies() = runComposeUiTest {
    val edits = FakeEdits()
    edits.current.value =
      AgentEditSession(
        id = "s1",
        drafts = listOf(AgentEditSession.Draft("d1", "Pump tile", config = draftConfig)),
      )
    show(edits, mutableListOf(), writeBlockedReason = "Read-only mode is on.")
    onNodeWithText("New session").performClick()
    waitUntil { onAllNodesWithText("Use it").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Use it").assertIsNotEnabled()
    onNodeWithText("Read-only mode is on. Drafts can be reviewed but not applied.").assertExists()
    onNodeWithText("card:tile:\"switch.pump\"").assertExists()
  }

  @Test
  fun scopesMatchBySection() {
    assertEquals(true, null sameAs EditScope())
    assertEquals(true, EditScope.section(0, 1, "A") sameAs EditScope.section(0, 1, "B"))
    assertEquals(false, EditScope.section(0, 1, "A") sameAs null)
  }

  @Test
  fun aShortWindowKeepsBackAndTheConversationInReach() = runComposeUiTest {
    val edits = FakeEdits().apply { baseline = draftConfig }
    // A landscape phone with the keyboard up: less height than a toolbar and the conversation.
    setContent {
      Box(Modifier.size(width = 640.dp, height = 200.dp)) {
        AgentEditScreen(
          edits = edits,
          urlPath = "garden",
          title = "Garden",
          snapshot = HaSnapshot(),
          onBack = {},
          onSharePrompt = {},
          onApplied = {},
          startScope = EditScope(),
        ) { _, card ->
          Text("card:${card.type}")
        }
      }
    }
    waitUntil {
      onAllNodesWithText("Tell the agent what to change").fetchSemanticsNodes().isNotEmpty()
    }
    for (node in
      listOf(
        onNodeWithContentDescription("Back"),
        onNodeWithText("Copy prompt"),
        onNodeWithText("Tell the agent what to change"),
        onNodeWithText("Send"),
      )) {
      node.performScrollTo().assertIsDisplayed()
      val bounds = node.getBoundsInRoot()
      assertTrue(bounds.bottom <= 200.dp, "${bounds.bottom} is past the window")
    }
    // A long message scrolls within the field, which stays two lines tall: all of it fits.
    val field = onNodeWithText("Tell the agent what to change")
    field.performTextInput(
      "Make the last field easy to reach\nKeep the reading clear\nKeep Send reachable"
    )
    field.performScrollTo()
    val bounds = field.getBoundsInRoot()
    assertTrue(
      bounds.bottom - bounds.top < 80.dp,
      "the field grew to ${bounds.bottom - bounds.top}",
    )
    assertTrue(bounds.bottom <= 200.dp, "${bounds.bottom} is past the window")
  }

  @Test
  fun aSectionReopensItsHomeAssistantOnlySession() = runComposeUiTest {
    val earlier =
      AgentEditSession(
        id = "ha-only",
        urlPath = "garden",
        request = "Pumps",
        baseline = draftConfig,
        scope = EditScope.section(0, 0, "Pumps"),
      )
    val edits = FakeEdits(listOf(earlier), openHidesUnrelayed = true)
    show(edits, mutableListOf(), startScope = EditScope.section(0, 0, "Pumps"))
    waitUntil { edits.current.value != null }
    assertEquals("ha-only", edits.current.value!!.id)
    assertTrue(edits.calls.none { it.startsWith("start:") }, "no second session")
  }

  @Test
  fun aSectionScopeResolvesNamedViews() {
    val dashboard =
      previewDashboard(
        Json.parseToJsonElement(
            """{"views":[{"path":"home","type":"sections","sections":[]},
              {"path":"garden","type":"sections","sections":[]},{"type":"sections"}]}"""
          )
          .jsonObject
      )!!
    fun key(path: String?) = CardKey("d", path, sectionIndex = 0, cardIndex = 0, type = "tile")
    val garden = EditScope.section(1, 0, "Pumps")
    assertTrue(sameView(key("garden"), garden, dashboard))
    assertFalse(sameView(key("home"), garden, dashboard), "section 0 of another named view")
    assertTrue(sameView(key("view-2"), EditScope.section(2, 0, "X"), dashboard))
  }

  @Test
  fun anAgentJoinedThroughHomeAssistantIsShownOverAnEmptyRelay() {
    val relay = AgentRelayState(version = 1, kind = "dashboard", title = "t")
    val direct = relay.copy(agent = AgentPresence(joined = true, connected = true, name = "Agent"))
    assertEquals(direct, shownLive(relay, direct))
    assertEquals(relay, shownLive(relay, null))
    val relayJoined = relay.copy(agent = AgentPresence(joined = true, name = "Claude"))
    assertEquals(relayJoined, shownLive(relayJoined, direct))
    assertEquals(direct, shownLive(null, direct))
  }
}
