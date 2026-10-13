package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.CardStudioClient
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

class CardStudioLifecycleTest {
  private val card =
    CardConfig(
      "entities",
      Json.parseToJsonElement(
          """{"type":"entities","title":"Kitchen","entities":["sensor.private_temp"]}"""
        )
        .jsonObject,
    )

  private fun snapshot(value: String) =
    HaSnapshot(states = mapOf("sensor.private_temp" to EntityState("sensor.private_temp", value)))

  private fun library(): File {
    val dir = Files.createTempDirectory("studio-lifecycle").toFile()
    DesignPublisher.publish(emptyList(), serial = 1, nowEpochMs = 1_000).files.forEach {
      (path, bytes) ->
      File(dir, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }
    return dir
  }

  @Test
  fun expiredContractRecoveryWorksAcrossTheRealClientAndServer() = testApplication {
    val dir = library()
    var now = 0L
    val seeds = CardStudioDrafts { now }
    val http = createClient { followRedirects = false }
    val config = UiBuilderConfig("https://builder.test", "https://builder.test", "operator")
    application {
      routing {
        cardStudioRoutes(
          LibraryHolder(dir),
          UiBuilderIntegration(config, UiBuilderClient(config, http)),
          null,
          seeds = seeds,
        )
      }
    }
    val studio = CardStudioClient("https://localhost", http.engine)
    try {
      val original = studio.seed(card, snapshot("21"))
      val edited = JsonObject(original + ("title" to JsonPrimitive("My layout")))
      now = 600_000
      val opened = studio.openDraft(edited, card, snapshot("22"))
      assertNotEquals(original["id"], opened.document["id"])
      assertEquals(edited["nodes"], opened.document["nodes"])
      assertEquals("My layout", opened.document["title"]!!.jsonPrimitive.content)
      assertTrue(opened.document["stateVariables"].toString().contains("22"))
      assertFalse(opened.document.toString().contains("private_temp"))
      assertEquals(HttpStatusCode.OK, client.get(opened.url).status)
    } finally {
      studio.close()
      dir.deleteRecursively()
    }
  }

  @Test
  fun concurrentBrowserPostsCreateOnlyOneDesign() = testApplication {
    val dir = library()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val creates = AtomicInteger()
    externalServices {
      hosts("https://builder.test") {
        routing {
          studioCatalogFixture()
          put("/api/ui-builder/v1/designs/{id}") {
            creates.incrementAndGet()
            entered.complete(Unit)
            release.await()
            call.respondText("created", status = HttpStatusCode.Created)
          }
        }
      }
    }
    val http = createClient { followRedirects = false }
    val config = UiBuilderConfig("https://builder.test", "https://builder.test", "operator")
    application {
      designServer(LibraryHolder(dir), UiBuilderIntegration(config, UiBuilderClient(config, http)))
    }
    val studio = CardStudioClient("https://localhost", http.engine)
    val browser = createClient { followRedirects = false }
    try {
      val link = studio.handoff(studio.seed(card, snapshot("21")))
      coroutineScope {
        val first = async {
          browser.post(link) { header(HttpHeaders.Cookie, "design-server-edit=operator") }
        }
        try {
          entered.await()
          assertEquals(
            HttpStatusCode.Conflict,
            browser.post(link) { header(HttpHeaders.Cookie, "design-server-edit=operator") }.status,
          )
          assertEquals(1, creates.get())
        } finally {
          release.complete(Unit)
        }
        assertEquals(HttpStatusCode.Found, first.await().status)
      }
      assertEquals(
        HttpStatusCode.Gone,
        browser.post(link) { header(HttpHeaders.Cookie, "design-server-edit=operator") }.status,
      )
      assertEquals(1, creates.get())
    } finally {
      release.complete(Unit)
      studio.close()
      dir.deleteRecursively()
    }
  }

  @Test
  fun failedBuilderCreateCanRetryTheSameHandoff() = testApplication {
    val dir = library()
    val creates = AtomicInteger()
    externalServices {
      hosts("https://builder.test") {
        routing {
          studioCatalogFixture(failFirstLookup = true)
          put("/api/ui-builder/v1/designs/{id}") {
            val status =
              if (creates.incrementAndGet() == 1) HttpStatusCode.ServiceUnavailable
              else HttpStatusCode.Created
            call.respondText("operator-private-preview", status = status)
          }
        }
      }
    }
    val http = createClient { followRedirects = false }
    val config = UiBuilderConfig("https://builder.test", "https://builder.test", "operator")
    application {
      designServer(LibraryHolder(dir), UiBuilderIntegration(config, UiBuilderClient(config, http)))
    }
    val studio = CardStudioClient("https://localhost", http.engine)
    val browser = createClient { followRedirects = false }
    try {
      val link = studio.handoff(studio.seed(card, snapshot("21")))
      val failed = browser.post(link) { header(HttpHeaders.Cookie, "design-server-edit=operator") }
      assertEquals(HttpStatusCode.BadGateway, failed.status)
      assertFalse(failed.bodyAsText().contains("operator-private-preview"))
      assertFalse(failed.bodyAsText().contains("private catalog error"))
      assertEquals(0, creates.get())
      assertEquals(
        HttpStatusCode.BadGateway,
        browser.post(link) { header(HttpHeaders.Cookie, "design-server-edit=operator") }.status,
      )
      assertEquals(1, creates.get())
      assertEquals(HttpStatusCode.OK, browser.get(link).status)
      assertEquals(
        HttpStatusCode.Found,
        browser.post(link) { header(HttpHeaders.Cookie, "design-server-edit=operator") }.status,
      )
      assertEquals(2, creates.get())
    } finally {
      studio.close()
      dir.deleteRecursively()
    }
  }

  @Test
  fun malformedCardsAreRejected() = testApplication {
    val dir = library()
    val http = createClient { followRedirects = false }
    val config = UiBuilderConfig("https://builder.test", "https://builder.test", "operator")
    application {
      designServer(LibraryHolder(dir), UiBuilderIntegration(config, UiBuilderClient(config, http)))
    }
    try {
      for (body in
        listOf("not-json", "{}", "[]", "{\"type\":\"tile\",\"entity\":[]}", " ".repeat(128_001))) {
        assertEquals(
          HttpStatusCode.BadRequest,
          client.post("/api/studio/seed") { setBody(body) }.status,
        )
      }
    } finally {
      http.close()
      dir.deleteRecursively()
    }
  }

  @Test
  fun missingBuilderReturnsServiceUnavailableBeforeCreatingPreviews() = testApplication {
    val dir = library()
    application { designServer(LibraryHolder(dir)) }
    try {
      assertEquals(
        HttpStatusCode.ServiceUnavailable,
        client.post("/api/studio/seed") { setBody("{}") }.status,
      )
      assertEquals(
        HttpStatusCode.ServiceUnavailable,
        client.post("/api/studio/handoffs") { setBody("{}") }.status,
      )
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun crossOriginWasmCanPreflightAndCreateAStudioHandoff() = testApplication {
    val dir = library()
    val http = createClient { followRedirects = false }
    val config = UiBuilderConfig("https://builder.test", "https://builder.test", "operator")
    application {
      designServer(LibraryHolder(dir), UiBuilderIntegration(config, UiBuilderClient(config, http)))
    }
    val origin = "https://yschimke.github.io"
    try {
      for (path in listOf("/api/studio/seed", "/api/studio/handoffs")) {
        val preflight =
          client.options(path) {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type")
          }
        assertEquals(HttpStatusCode.NoContent, preflight.status)
        assertEquals("*", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(preflight.headers[HttpHeaders.AccessControlAllowMethods]!!.contains("POST"))
        assertEquals("Content-Type", preflight.headers[HttpHeaders.AccessControlAllowHeaders])
        assertNull(preflight.headers[HttpHeaders.AccessControlAllowCredentials])
      }
      val seed =
        client.post("/api/studio/seed") {
          header(HttpHeaders.Origin, origin)
          header(HttpHeaders.ContentType, "application/json")
          setBody("""{"type":"entities","entities":["sensor.temp"]}""")
        }
      assertEquals(HttpStatusCode.OK, seed.status)
      assertEquals("*", seed.headers[HttpHeaders.AccessControlAllowOrigin])
      val handoff =
        client.post("/api/studio/handoffs") {
          header(HttpHeaders.Origin, origin)
          header(HttpHeaders.ContentType, "application/json")
          setBody(seed.bodyAsText())
        }
      assertEquals(HttpStatusCode.OK, handoff.status)
      assertEquals("*", handoff.headers[HttpHeaders.AccessControlAllowOrigin])
      val path =
        Json.parseToJsonElement(handoff.bodyAsText()).jsonObject["path"]!!.jsonPrimitive.content
      assertNull(
        client
          .options(path) { header(HttpHeaders.Origin, origin) }
          .headers[HttpHeaders.AccessControlAllowOrigin]
      )
      assertNull(
        client
          .get(path) { header(HttpHeaders.Origin, origin) }
          .headers[HttpHeaders.AccessControlAllowOrigin]
      )
      assertNull(
        client
          .post(path) { header(HttpHeaders.Origin, origin) }
          .headers[HttpHeaders.AccessControlAllowOrigin]
      )
      val rejected =
        client.post("/api/studio/handoffs") {
          header(HttpHeaders.Origin, origin)
          setBody("not JSON")
        }
      assertEquals(HttpStatusCode.BadRequest, rejected.status)
      assertEquals("*", rejected.headers[HttpHeaders.AccessControlAllowOrigin])
    } finally {
      http.close()
      dir.deleteRecursively()
    }
  }

  @Test
  fun claimsAreReleasedForRetryAndExpirationStillApplies() {
    var now = 0L
    val drafts = CardStudioDrafts { now }
    val document = buildJsonObject { put("title", "Kitchen") }
    val token = drafts.put(document)
    assertEquals(CardStudioDrafts.Claim.Ready(document), drafts.claim(token))
    assertEquals(CardStudioDrafts.Claim.Busy, drafts.claim(token))
    drafts.release(token)
    assertEquals(CardStudioDrafts.Claim.Ready(document), drafts.claim(token))
    now = 600_000
    assertEquals(CardStudioDrafts.Claim.Missing, drafts.claim(token))
    drafts.release(token)
    assertNull(drafts.get(token))
  }
}
