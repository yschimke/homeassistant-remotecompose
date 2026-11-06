package ee.schimke.ha.designserver

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.*
import kotlinx.serialization.json.*

class DashboardAgentRoutesTest {
  @Test
  fun draftRequiresAgentCapabilityAndIsNeverApplied() = testApplication {
    var now = 0L
    application { routing { dashboardAgentRoutes(sessions = DashboardAgentSessions { now }) } }
    val baseline = Json.parseToJsonElement("""{"views":[{"title":"Home"}]}""").jsonObject
    val create =
      client.post("/api/studio/dashboard-sessions") {
        setBody(
          buildJsonObject {
            put("request", "Tidy it")
            put("dashboard", baseline)
          }
            .toString(),
        )
      }
    assertEquals(HttpStatusCode.OK, create.status)
    assertEquals("no-store", create.headers[HttpHeaders.CacheControl])
    val access = Json.parseToJsonElement(create.bodyAsText()).jsonObject
    val owner = access.getValue("ownerToken").jsonPrimitive.content
    val agent = access.getValue("agentToken").jsonPrimitive.content
    val tools =
      client.post("/api/studio/dashboard-mcp") {
        header(HttpHeaders.Authorization, "Bearer $agent")
        setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
      }
    assertEquals(HttpStatusCode.OK, tools.status)
    assertTrue(tools.bodyAsText().contains("get_dashboard_request"))
    assertEquals(
      HttpStatusCode.Unauthorized,
      client
        .post("/api/studio/dashboard-mcp") {
          header(HttpHeaders.Authorization, "Bearer $owner")
          setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }
        .status,
    )
    val changed = Json.parseToJsonElement("""{"views":[{"title":"Garden"}]}""").jsonObject
    suspend fun submit(token: String, revision: Int, draft: JsonObject) =
      client.post("/api/studio/dashboard-session") {
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(
          buildJsonObject {
            put("revision", revision)
            put("dashboard", draft)
            put("summary", "Garden")
          }
            .toString(),
        )
      }
    assertEquals(HttpStatusCode.NotFound, submit(owner, 0, changed).status)
    assertEquals(HttpStatusCode.OK, submit(agent, 0, changed).status)
    assertEquals(HttpStatusCode.Conflict, submit(agent, 0, baseline).status)
    assertEquals(HttpStatusCode.Conflict, submit(agent, 1, buildJsonObject {}).status)
    val note =
      client.post("/api/studio/dashboard-session/note") {
        header(HttpHeaders.Authorization, "Bearer $owner")
        setBody("""{"text":"Bigger"}""")
      }
    assertEquals(HttpStatusCode.OK, note.status)
    val result =
      client.get("/api/studio/dashboard-session") {
        header(HttpHeaders.Authorization, "Bearer $agent")
      }
    val body = Json.parseToJsonElement(result.bodyAsText()).jsonObject
    assertEquals(changed, body["dashboard"])
    assertEquals("Bigger", body.getValue("notes").jsonArray[0].jsonPrimitive.content)
    assertEquals(
      HttpStatusCode.NotFound,
      client
        .delete("/api/studio/dashboard-session") {
          header(HttpHeaders.Authorization, "Bearer $agent")
        }
        .status,
    )
    assertEquals(
      HttpStatusCode.OK,
      client
        .delete("/api/studio/dashboard-session") {
          header(HttpHeaders.Authorization, "Bearer $owner")
        }
        .status,
    )
    assertEquals(
      HttpStatusCode.NotFound,
      client
        .get("/api/studio/dashboard-session") { header(HttpHeaders.Authorization, "Bearer $agent") }
        .status,
    )
    val expiring =
      client.post("/api/studio/dashboard-sessions") {
        setBody(
          buildJsonObject {
            put("request", "Again")
            put("dashboard", baseline)
          }
            .toString(),
        )
      }
    val expiringAgent =
      Json.parseToJsonElement(expiring.bodyAsText())
        .jsonObject
        .getValue("agentToken")
        .jsonPrimitive
        .content
    now += 60 * 60_000
    assertEquals(
      HttpStatusCode.NotFound,
      client
        .get("/api/studio/dashboard-session") {
          header(HttpHeaders.Authorization, "Bearer $expiringAgent")
        }
        .status,
    )
  }
}
