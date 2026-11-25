package ee.schimke.ha.client

import ee.schimke.ha.client.dashboard.DashboardAgent
import ee.schimke.ha.client.dashboard.DashboardConflictException
import ee.schimke.ha.client.dashboard.EditScope
import ee.schimke.ha.client.dashboard.scopeLabel
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The Terrazzo integration's edit session commands, as the phone and panel call them. */
class HaClientAgentEditsTest {

  private fun session(status: String = "open", drafts: String = "[]") =
    """{"session":{"session_id":"s1","url_path":"garden","request":"Add the pump",
      "status":"$status","notes":[],"applied":null,"drafts":$drafts,
      "baseline":{"views":[]},"created_at":1,"updated_at":1,"expires_at":2}}"""

  /** A fake Home Assistant answering each command with [answer]'s frames (results and events). */
  private fun fakeHa(
    commands: MutableList<JsonObject>,
    answer: (JsonObject, Int) -> List<String>,
    block: suspend (HaClient) -> Unit,
  ) = testApplication {
    install(WebSockets)
    routing {
      webSocket("/api/websocket") {
        send(Frame.Text("""{"type":"auth_required"}"""))
        incoming.receive()
        send(Frame.Text("""{"type":"auth_ok"}"""))
        for (frame in incoming) {
          val command = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
          synchronized(commands) { commands += command }
          answer(command, command.getValue("id").jsonPrimitive.int).forEach { send(Frame.Text(it)) }
        }
      }
    }
    val client = HaClient(HaConfig("http://localhost", "token"), createClient {}.engine)
    try {
      client.connect()
      block(client)
    } finally {
      client.close()
    }
  }

  private val JsonObject.type
    get() = getValue("type").jsonPrimitive.content

  private fun result(id: Int, body: String) =
    """{"id":$id,"type":"result","success":true,"result":$body}"""

  @Test
  fun startingASessionReturnsItAndThePrompt() {
    val commands = mutableListOf<JsonObject>()
    fakeHa(
      commands,
      { _, id ->
        val body = session().removeSuffix("}") + ""","prompt":"Connect to /api/terrazzo/mcp"}"""
        listOf(result(id, body))
      },
    ) { client ->
      val started = client.agentEdits.start("garden", "Add the pump")
      assertEquals("s1", started.session.id)
      assertEquals("garden", started.session.urlPath)
      assertTrue(started.session.isOpen)
      assertEquals("Connect to /api/terrazzo/mcp", started.prompt)
    }
    val command = commands.single()
    assertEquals("terrazzo/edit/start", command.type)
    assertEquals("Add the pump", command.getValue("request").jsonPrimitive.content)
  }

  @Test
  fun theDashboardAgentIsReadAndOffForAnOlderIntegration() {
    fakeHa(
      mutableListOf(),
      { _, id ->
        listOf(
          result(
            id,
            """{"enabled":true,"agent_id":"conversation.google_generative_ai",
              "name":"Google Generative AI"}""",
          ),
        )
      },
    ) { client ->
      val agent = client.terrazzoDashboardAgent()
      assertTrue(agent.enabled)
      assertEquals("conversation.google_generative_ai", agent.agentId)
      assertEquals("Google Generative AI", agent.displayName)
    }
    fakeHa(
      mutableListOf(),
      { _, id ->
        listOf(
          """{"id":$id,"type":"result","success":false,
            "error":{"code":"unknown_command","message":"Unknown command."}}""",
        )
      },
    ) { client ->
      assertEquals(DashboardAgent.Off, client.terrazzoDashboardAgent())
    }
  }

  @Test
  fun theDashboardChatSendsWhatIsOnScreenAndReadsTheDraftsSession() {
    val commands = mutableListOf<JsonObject>()
    fakeHa(
      commands,
      { _, id ->
        listOf(
          result(
            id,
            """{"speech":"I turned the lights off.","error":false,"conversation_id":"c1",
              "session_id":"s1","drafted":true,
              "steps":[{"tool":"submit_draft","input":"Lights","output":"Draft submitted","ok":true}]}""",
          ),
        )
      },
    ) { client ->
      val reply =
        client.terrazzoAssist(
          "Turn these off",
          urlPath = "garden",
          view = "Lights",
          entityIds = List(100) { "light.l$it" },
          conversationId = "c0",
        )
      assertEquals("I turned the lights off.", reply.speech)
      assertEquals("c1", reply.conversationId)
      assertEquals("s1", reply.sessionId)
      assertTrue(reply.drafted)
      assertEquals("submit_draft", reply.steps.single().tool)
    }
    val command = commands.single()
    assertEquals("terrazzo/assist", command.type)
    assertEquals("garden", command.getValue("url_path").jsonPrimitive.content)
    assertEquals("Lights", command.getValue("view").jsonPrimitive.content)
    assertEquals("c0", command.getValue("conversation_id").jsonPrimitive.content)
    assertEquals(60, command.getValue("entities").jsonArray.size)
    assertTrue("session_id" !in command)
  }

  @Test
  fun theChatIsOfferedOnlyWhenTheIntegrationHasIt() {
    assertTrue(DashboardAgent(enabled = true, chat = true).chatAvailable)
    assertTrue(!DashboardAgent(enabled = true).chatAvailable)
    assertTrue(!DashboardAgent(enabled = false, chat = true).chatAvailable)
  }

  @Test
  fun aNewDashboardIsStartedWithItsScopeAndListedWhileBeingBuilt() {
    val commands = mutableListOf<JsonObject>()
    val building =
      """{"session_id":"s2","url_path":"dashboard-garden","request":"Build it","status":"open",
        "notes":[],"applied":null,"drafts":[],"created_at":1,"updated_at":1,"expires_at":2,
        "scope":{"kind":"new_dashboard","title":"Garden","label":"Garden"}}"""
    fakeHa(
      commands,
      { command, id ->
        if (command.type == "terrazzo/edit/list") listOf(result(id, """{"sessions":[$building]}"""))
        else listOf(result(id, """{"session":$building,"prompt":""}"""))
      },
    ) { client ->
      val started =
        client.agentEdits.start(null, "Build it", EditScope.newDashboard("Garden")).session
      assertTrue(started.scope!!.isNewDashboard)
      assertEquals("Garden", started.scopeLabel("ignored"))
      assertEquals(listOf("s2"), client.agentEdits.newDashboards().map { it.id })
    }
    val (start, list) = commands
    assertEquals(
      "new_dashboard",
      start.getValue("scope").jsonObject.getValue("kind").jsonPrimitive.content,
    )
    assertEquals(
      "Garden",
      start.getValue("scope").jsonObject.getValue("title").jsonPrimitive.content,
    )
    assertEquals(true, list.getValue("new_dashboards").jsonPrimitive.content.toBoolean())
  }

  @Test
  fun aRelayedDraftUsesTheSignedInHomeAssistantSession() {
    val commands = mutableListOf<JsonObject>()
    fakeHa(commands, { _, id -> listOf(result(id, session())) }) { client ->
      val draft = Json.parseToJsonElement("""{"views":[{"title":"Garden"}]}""").jsonObject
      client.relayAgentDraft("s1", draft, "Garden", "cloud:1")
      val command = commands.single()
      assertEquals("terrazzo/edit/submit", command.type)
      assertEquals("s1", command.getValue("session_id").jsonPrimitive.content)
      assertEquals(draft, command["config"])
      assertEquals("Garden", command.getValue("summary").jsonPrimitive.content)
      assertEquals("cloud:1", command.getValue("relay_id").jsonPrimitive.content)
    }
  }

  @Test
  fun aConflictingApplyIsAConflict() {
    fakeHa(
      mutableListOf(),
      { _, id ->
        listOf(
          """{"id":$id,"type":"result","success":false,
            "error":{"code":"conflict","message":"This dashboard changed in Home Assistant"}}""",
        )
      },
    ) { client ->
      val conflict =
        assertFailsWith<DashboardConflictException> { client.agentEdits.apply("s1", "d1") }
      assertEquals("This dashboard changed in Home Assistant", conflict.message)
    }
  }

  @Test
  fun aRefusedApplyKeepsHomeAssistantsCode() {
    fakeHa(
      mutableListOf(),
      { _, id ->
        listOf(
          """{"id":$id,"type":"result","success":false,
            "error":{"code":"unauthorized","message":"Unauthorized"}}""",
        )
      },
    ) { client ->
      val refused = assertFailsWith<HaCommandException> { client.agentEdits.apply("s1", "d1") }
      assertEquals("unauthorized", refused.code)
    }
  }

  @Test
  fun watchingASessionSeesTheLatestAndUnsubscribesWhenDone() {
    val commands = mutableListOf<JsonObject>()
    val draft = """[{"id":"d1","summary":"Pump card","by":"u","at":3,"config":{"views":[]}}]"""
    fakeHa(
      commands,
      { command, id ->
        when (command.type) {
          // The integration acks, then sends the session now; a draft follows.
          "terrazzo/edit/subscribe" ->
            listOf(
              result(id, "null"),
              """{"id":$id,"type":"event","event":${session()}}""",
              """{"id":$id,"type":"event","event":${session(drafts = draft)}}""",
            )
          else -> listOf(result(id, "null"))
        }
      },
    ) { client ->
      // The session is state: a watcher may skip one it was too late for, never the latest.
      val drafted =
        withTimeout(10_000) { client.agentEdits.watch("s1").first { it.drafts.isNotEmpty() } }
      assertEquals("Pump card", drafted.latestDraft?.summary)
      assertEquals<Any?>(Json.parseToJsonElement("""{"views":[]}"""), drafted.latestDraft?.config)
      withTimeout(10_000) {
        while (synchronized(commands) { commands.none { it.type == "unsubscribe_events" } }) {
          kotlinx.coroutines.yield()
        }
      }
    }
    val subscribe = commands.first { it.type == "terrazzo/edit/subscribe" }
    val unsubscribe = commands.first { it.type == "unsubscribe_events" }
    assertEquals("s1", subscribe.getValue("session_id").jsonPrimitive.content)
    assertEquals(
      subscribe.getValue("id").jsonPrimitive.int,
      unsubscribe.getValue("subscription").jsonPrimitive.int,
    )
  }

  @Test
  fun aWatchStartedLateStillSeesTheCurrentSession() {
    fakeHa(
      mutableListOf(),
      { command, id ->
        if (command.type == "terrazzo/edit/subscribe")
          listOf(result(id, "null"), """{"id":$id,"type":"event","event":${session("applied")}}""")
        else listOf(result(id, "null"))
      },
    ) { client ->
      val first = withTimeout(10_000) { client.agentEdits.watch("s1").first() }
      assertTrue(first.isApplied)
    }
  }
}
