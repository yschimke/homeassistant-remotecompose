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
    webSocket("/api/websocket") {
      send(Frame.Text("""{"type":"auth_required"}"""))
      incoming.receive()
      send(Frame.Text("""{"type":"auth_ok"}"""))
      for (frame in incoming) {
        val cmd = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
        val type = cmd.getValue("type").jsonPrimitive.content
        commands += type
        if (type == "terrazzo/edit/submit") draft = cmd.getValue("config").jsonObject
        val session = buildJsonObject {
          put("session_id", "s1")
          put("request", "Rename")
          put("baseline", baseline)
          put("status", if (type == "terrazzo/edit/apply") "applied" else "open")
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
      }
    }
  }

  @Test
  fun upstreamDraftIsRelayedForReviewBeforeHomeAssistantApply() = testApplication {
    val commands = mutableListOf<String>()
    install(WebSockets)
    routing {
      dashboardAgentRoutes()
      fakeHome(commands)
    }
    val http = createClient {}
    val home = HaClient(HaConfig("http://localhost", "home-token"), http.engine)
    val relay = CloudDashboardAgentEdits(home, "https://localhost", http.engine)
    try {
      home.connect()
      val started = relay.start(null, "Rename")
      val agent = Regex("Bearer ([A-Za-z0-9_-]{32})").find(started.prompt)!!.groupValues[1]
      // Returning to the edit screen uses the same in-memory relay and recovers the handoff.
      assertEquals(listOf("s1"), relay.open(null).map { it.id })
      assertEquals(started.prompt, relay.resume("s1").prompt)
      val changed = Json.parseToJsonElement("""{"views":[{"title":"Garden"}]}""").jsonObject
      val response =
        http.post("https://localhost/api/studio/dashboard-session") {
          header(HttpHeaders.Authorization, "Bearer $agent")
          setBody(
            buildJsonObject {
              put("revision", 0)
              put("dashboard", changed)
              put("summary", "Renamed")
            }
              .toString()
          )
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val received = withTimeout(10_000) { relay.watch("s1").first { it.drafts.isNotEmpty() } }
      assertEquals(changed, received.drafts.single().config)
      assertFalse(commands.contains("terrazzo/edit/apply"))
      relay.apply("s1", "d1")
      assertTrue(commands.contains("terrazzo/edit/apply"))
    } finally {
      relay.close()
      home.close()
      http.close()
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
}
