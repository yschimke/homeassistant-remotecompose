package ee.schimke.ha.designserver

import ee.schimke.ha.client.HaClient
import ee.schimke.ha.client.HaConfig
import ee.schimke.ha.client.dashboard.CloudDashboardAgentEdits
import io.ktor.client.request.*
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
  @Test
  fun upstreamDraftIsRelayedForReviewBeforeHomeAssistantApply() = testApplication {
    val commands = mutableListOf<String>()
    val baseline = Json.parseToJsonElement("""{"views":[{"title":"Home"}]}""").jsonObject
    var draft: JsonObject? = null
    install(WebSockets)
    routing {
      dashboardAgentRoutes()
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
}
