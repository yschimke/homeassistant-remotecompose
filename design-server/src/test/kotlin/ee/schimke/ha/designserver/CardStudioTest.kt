package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.DesignPublisher
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
