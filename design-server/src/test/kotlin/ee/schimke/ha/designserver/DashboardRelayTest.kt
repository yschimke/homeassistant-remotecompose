package ee.schimke.ha.designserver

import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.agent.AgentGrantStore
import ee.schimke.ha.client.dashboard.CloudDashboardAgentEdits
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

class DashboardRelayTest {
  private val baseline = Json.parseToJsonElement("""{"views":[{"title":"Home"}]}""").jsonObject

  /** A Home Assistant that answers edit-session commands, recording them in [commands]. */
  private fun Route.fakeHome(commands: MutableList<String>) {
    var draft: JsonObject? = null
    var cloud: JsonObject? = null
    var status = "open"
    webSocket("/api/websocket") {
      send(Frame.Text("""{"type":"auth_required"}"""))
      incoming.receive()
      send(Frame.Text("""{"type":"auth_ok"}"""))
      for (frame in incoming) {
        val cmd = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
        val type = cmd.getValue("type").jsonPrimitive.content
        commands += type
        if (type == "terrazzo/edit/submit") draft = cmd.getValue("config").jsonObject
        if (type == "terrazzo/edit/apply") status = "applied"
        if (type == "terrazzo/edit/discard") status = "discarded"
        if (type == "terrazzo/edit/cloud")
          cloud = buildJsonObject {
            put("server", cmd.getValue("server"))
            put("owner", cmd.getValue("owner"))
          }
        val session = buildJsonObject {
          put("session_id", "s1")
          put("request", "Rename")
          put("baseline", baseline)
          put("status", status)
          cloud?.let { put("cloud", it) }
          putJsonArray("drafts") {
            draft?.let {
              add(
                buildJsonObject {
                  put("id", "d1")
                  put("config", it)
                }
              )
            }
          }
        }
        send(
          Frame.Text(
            buildJsonObject {
              put("id", cmd.getValue("id"))
              put("type", "result")
              put("success", true)
              putJsonObject("result") {
                put("session", session)
                if (type == "terrazzo/edit/list") putJsonArray("sessions") { add(session) }
              }
            }
              .toString()
          )
        )
        if (type == "terrazzo/edit/subscribe")
          send(
            Frame.Text(
              buildJsonObject {
                put("id", cmd.getValue("id"))
                put("type", "event")
                putJsonObject("event") { put("session", session) }
              }
                .toString()
            )
          )
      }
    }
  }

  @Test
  fun relayJoinsAgentByCodeAndWakesTheScreenOnADraft() = testApplication {
    val commands = mutableListOf<String>()
    val agentRelay = AgentRelay(AgentDesignSessions(), DashboardAgentSessions(), maxWaitMs = 5_000)
    install(WebSockets)
    routing {
      dashboardAgentRoutes(agentRelay)
      agentRelayRoutes(agentRelay)
      fakeHome(commands)
    }
    val http = createClient {}
    val home = HaClient(HaConfig("http://localhost", "home-token"), http.engine)
    val grants = AgentGrantStore.memory()
    val relay = CloudDashboardAgentEdits(home, "https://localhost", http.engine, grants)
    try {
      home.connect()
      val started = relay.start(null, "Rename")
      // No capability is handed over: the screen shows a code instead of a prompt.
      assertEquals("", started.prompt)
      assertFalse(grants.load().isNullOrEmpty(), "the owner capability survives a reload")
      val code = assertNotNull(relay.live("s1").value?.joinCode)
      suspend fun tool(name: String, args: JsonObject) =
        Json.parseToJsonElement(
            http
              .post("https://localhost/api/agent/mcp") {
                setBody(
                  buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "tools/call")
                    putJsonObject("params") {
                      put("name", name)
                      put("arguments", args)
                    }
                  }
                    .toString()
                )
              }
              .bodyAsText()
          )
          .jsonObject
          .getValue("result")
          .jsonObject
          .getValue("structuredContent")
          .jsonObject
      val session =
        tool("join_session", buildJsonObject { put("code", code) })
          .getValue("session")
          .jsonPrimitive
          .content
      val changed = Json.parseToJsonElement("""{"views":[{"title":"Garden"}]}""").jsonObject
      tool(
        "submit_draft",
        buildJsonObject {
          put("session", session)
          put("revision", 0)
          put("dashboard", changed)
          put("summary", "Renamed")
        },
      )
      val received = withTimeout(10_000) { relay.watch("s1").first { it.drafts.isNotEmpty() } }
      assertEquals(changed, received.drafts.single().config)
      val live = relay.live("s1").value!!
      assertTrue(live.agent.connected)
      assertEquals("Renamed", live.messages.single().text)

      relay.comment("s1", "Bigger camera", "Front door")
      val feedback = tool("await_feedback", buildJsonObject { put("session", session) })
      assertEquals(
        "Front door",
        feedback
          .getValue("feedback")
          .jsonArray
          .single()
          .jsonObject
          .getValue("target")
          .jsonPrimitive
          .content,
      )

      relay.apply("s1", "d1")
      assertTrue(commands.contains("terrazzo/edit/apply"))
      val done =
        tool(
          "await_feedback",
          buildJsonObject {
            put("session", session)
            put("since", feedback.getValue("cursor").jsonPrimitive.long)
          },
        )
      assertEquals("accepted", done.getValue("decision").jsonPrimitive.content)
    } finally {
      relay.close()
      home.close()
      http.close()
    }
  }

  @Test
  fun anotherClientPicksTheRequestUpFromHomeAssistant() = testApplication {
    val commands = mutableListOf<String>()
    val agentRelay = AgentRelay(AgentDesignSessions(), DashboardAgentSessions(), maxWaitMs = 5_000)
    install(WebSockets)
    routing {
      dashboardAgentRoutes(agentRelay)
      agentRelayRoutes(agentRelay)
      fakeHome(commands)
    }
    val http = createClient {}
    val home = HaClient(HaConfig("http://localhost", "home-token"), http.engine)
    val phone = CloudDashboardAgentEdits(home, "https://localhost", http.engine)
    // A browser that never saw the request: nothing kept on this device.
    val browserGrants = AgentGrantStore.memory()
    val browser = CloudDashboardAgentEdits(home, "https://localhost", http.engine, browserGrants)
    try {
      home.connect()
      phone.start(null, "Rename")
      assertTrue(commands.contains("terrazzo/edit/cloud"), "the request is linked to the session")
      assertEquals(listOf("s1"), browser.open(null).map { it.id })
      assertFalse(browserGrants.load().isNullOrEmpty(), "the browser keeps what it picked up")
      // And it can act on the request, as the phone could.
      browser.newJoinCode("s1")
      assertNotNull(browser.live("s1").value?.joinCode)
      // Another design server's request isn't one this client can pick up.
      val elsewhere = CloudDashboardAgentEdits(home, "https://elsewhere.example", http.engine)
      assertEquals(emptyList(), elsewhere.open(null).map { it.id })
      elsewhere.close()
    } finally {
      phone.close()
      browser.close()
      home.close()
      http.close()
    }
  }

  @Test
  fun aStoppedSessionIsWatchedThroughHomeAssistant() = testApplication {
    val commands = mutableListOf<String>()
    val agentRelay = AgentRelay(AgentDesignSessions(), DashboardAgentSessions(), maxWaitMs = 5_000)
    install(WebSockets)
    routing {
      dashboardAgentRoutes(agentRelay)
      agentRelayRoutes(agentRelay)
      fakeHome(commands)
    }
    val http = createClient {}
    val home = HaClient(HaConfig("http://localhost", "home-token"), http.engine)
    val phone = CloudDashboardAgentEdits(home, "https://localhost", http.engine)
    val browser = CloudDashboardAgentEdits(home, "https://localhost", http.engine)
    try {
      home.connect()
      phone.start(null, "Rename")
      phone.discard("s1")
      // Its relay is revoked but its link stays: a recent session still opens, from Home Assistant.
      val seen = withTimeout(10_000) { browser.watch("s1").first() }
      assertEquals("discarded", seen.status)
    } finally {
      phone.close()
      browser.close()
      home.close()
      http.close()
    }
  }
}
