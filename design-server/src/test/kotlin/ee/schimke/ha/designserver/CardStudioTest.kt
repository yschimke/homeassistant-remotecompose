package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.DesignPublisher
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

class CardStudioTest {
  @Test
  fun draftsExpireAndCapacityIsBounded() {
    var now = 0L
    val drafts = CardStudioDrafts { now }
    val document = buildJsonObject { put("title", "Kitchen") }
    val token = drafts.put(document)
    assertEquals(32, token.length)
    assertEquals(document, drafts.get(token))
    repeat(127) { drafts.put(document) }
    assertFailsWith<IllegalStateException> { drafts.put(document) }
    now = 600_000
    assertNull(drafts.get(token))
    assertNotNull(drafts.put(document))
  }

  @Test
  fun appHandoffUsesCurrentValuesEscapesMarkupAndRequiresSignInToCreate() = testApplication {
    val dir = Files.createTempDirectory("studio-library").toFile()
    DesignPublisher.publish(emptyList(), serial = 1, nowEpochMs = 1_000).files.forEach {
      (path, bytes) ->
      File(dir, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }
    var created: JsonObject? = null
    externalServices {
      hosts("https://builder.test") {
        routing {
          studioCatalogFixture()
          put("/api/ui-builder/v1/designs/{id}") {
            assertEquals("*", call.request.headers[HttpHeaders.IfNoneMatch])
            created = Json.parseToJsonElement(call.receiveText()).jsonObject
            call.respondText("created", status = HttpStatusCode.Created)
          }
        }
      }
    }
    val http = createClient { followRedirects = false }
    val browser = createClient { followRedirects = false }
    val config = UiBuilderConfig("https://builder.test", "https://builder.test", "operator")
    application {
      designServer(LibraryHolder(dir), UiBuilderIntegration(config, UiBuilderClient(config, http)))
    }
    try {
      val response =
        client.post("/api/studio/seed") {
          setBody(
            """{"card":{"type":"entities","title":"Kitchen","entities":["sensor.s0"]},"states":{"sensor.s0":{"state":"21","attributes":{"friendly_name":"<script>bad</script>","unit_of_measurement":"°C"}}}}"""
          )
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
      val session =
        client.post("/api/studio/agent-sessions") {
          setBody(
            buildJsonObject {
              put("instruction", "Make it brighter")
              put("document", document)
            }
              .toString()
          )
        }
      assertEquals(HttpStatusCode.OK, session.status)
      assertEquals("no-store", session.headers[HttpHeaders.CacheControl])
      val access = Json.parseToJsonElement(session.bodyAsText()).jsonObject
      val owner = access.getValue("ownerToken").jsonPrimitive.content
      val agent = access.getValue("agentToken").jsonPrimitive.content
      val mcpList =
        client.post("/api/studio/agent-mcp") {
          header(HttpHeaders.Authorization, "Bearer $agent")
          setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }
      assertEquals(HttpStatusCode.OK, mcpList.status)
      assertTrue(mcpList.bodyAsText().contains("get_card_request"))
      assertEquals(
        HttpStatusCode.Unauthorized,
        client
          .post("/api/studio/agent-mcp") {
            header(HttpHeaders.Authorization, "Bearer $owner")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
          }
          .status,
      )
      val mcpRead =
        client.post("/api/studio/agent-mcp") {
          header(HttpHeaders.Authorization, "Bearer $agent")
          setBody(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_card_request","arguments":{}}}"""
          )
        }
      assertTrue(mcpRead.bodyAsText().contains("Make it brighter"))
      val read =
        client.get("/api/studio/agent-session") {
          header(HttpHeaders.Authorization, "Bearer $agent")
        }
      assertEquals(HttpStatusCode.OK, read.status)
      assertEquals(
        "Make it brighter",
        Json.parseToJsonElement(read.bodyAsText())
          .jsonObject["instruction"]!!
          .jsonPrimitive
          .content,
      )
      val draft = JsonObject(document + ("title" to JsonPrimitive("Brighter")))
      val submitted =
        client.post("/api/studio/agent-session") {
          header(HttpHeaders.Authorization, "Bearer $agent")
          setBody(
            buildJsonObject {
              put("revision", 0)
              put("document", draft)
            }
              .toString()
          )
        }
      assertEquals(HttpStatusCode.OK, submitted.status)
      val stale =
        client.post("/api/studio/agent-mcp") {
          header(HttpHeaders.Authorization, "Bearer $agent")
          setBody(
            buildJsonObject {
              put("jsonrpc", "2.0")
              put("id", 3)
              put("method", "tools/call")
              putJsonObject("params") {
                put("name", "submit_card_draft")
                putJsonObject("arguments") {
                  put("revision", 0)
                  put("document", draft)
                }
              }
            }
              .toString()
          )
        }
      assertTrue(stale.bodyAsText().contains("\"isError\":true"))
      val review =
        client.get("/api/studio/agent-session") {
          header(HttpHeaders.Authorization, "Bearer $owner")
        }
      assertEquals(draft, Json.parseToJsonElement(review.bodyAsText()).jsonObject["document"])
      assertEquals(
        HttpStatusCode.NotFound,
        client
          .delete("/api/studio/agent-session") {
            header(HttpHeaders.Authorization, "Bearer $agent")
          }
          .status,
      )
      assertEquals(
        HttpStatusCode.OK,
        client
          .delete("/api/studio/agent-session") {
            header(HttpHeaders.Authorization, "Bearer $owner")
          }
          .status,
      )
      assertEquals(
        HttpStatusCode.NotFound,
        client
          .get("/api/studio/agent-session") { header(HttpHeaders.Authorization, "Bearer $agent") }
          .status,
      )
      assertEquals(
        "ha-cards",
        document["catalogPin"]!!.jsonObject["systemId"]!!.jsonPrimitive.content,
      )
      assertTrue(document.toString().contains("21 °C"))
      val handoff = client.post("/api/studio/handoffs") { setBody(document.toString()) }
      assertEquals(HttpStatusCode.OK, handoff.status)
      val path =
        Json.parseToJsonElement(handoff.bodyAsText()).jsonObject["path"]!!.jsonPrimitive.content
      val page = client.get(path)
      assertTrue(
        page.headers["Content-Security-Policy"]!!.contains(
          "form-action 'self' https://builder.test;"
        )
      )
      assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
      assertFalse(page.bodyAsText().contains("<script>bad</script>"))
      assertTrue(page.bodyAsText().contains("&lt;script&gt;"))
      assertEquals(HttpStatusCode.Forbidden, client.post(path).status)
      val tampered =
        JsonObject(document + ("catalogPin" to buildJsonObject { put("systemId", "other") }))
      assertEquals(
        HttpStatusCode.BadRequest,
        client.post("/api/studio/handoffs") { setBody(tampered.toString()) }.status,
      )
      val forged = JsonObject(document + ("id" to JsonPrimitive("card-not-a-real-preview")))
      assertEquals(
        HttpStatusCode.Gone,
        client.post("/api/studio/handoffs") { setBody(forged.toString()) }.status,
      )
      assertNull(created)
      val opened = browser.post(path) { header(HttpHeaders.Cookie, "design-server-edit=operator") }
      assertEquals(HttpStatusCode.Found, opened.status)
      assertNotNull(created)
      assertNotEquals(document["id"], created!!["id"])
      assertEquals(
        Json.parseToJsonElement(
          """{"systemId":"ha-cards","catalogRevision":"sha256:published","capabilityDigest":"candidate","nativeRuntimeId":"published-runtime"}"""
        ),
        created!!["catalogPin"],
      )
      assertEquals(document["nodes"], created!!["nodes"])
      assertEquals(document["stateVariables"], created!!["stateVariables"])
      assertNull(created!!["template"])
      assertTrue(
        opened.headers[HttpHeaders.Location]!!.startsWith("https://builder.test/ui-builder/card-")
      )
      assertEquals(
        HttpStatusCode.Gone,
        browser.post(path) { header(HttpHeaders.Cookie, "design-server-edit=operator") }.status,
      )
    } finally {
      http.close()
      dir.deleteRecursively()
    }
  }
}
