package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*

class CatalogPublicationTest {
  private val bytes = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val key =
    DesignKey(
      "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = "baked:Material3:light",
    )
  private val source =
    Json.parseToJsonElement(
        """{"id":"admin-draft","revision":7,"catalogPin":{"systemId":"ha-cards","catalogRevision":"published-sha","nativeRuntimeId":"published-runtime","capabilityDigest":"candidate"},"nodes":{"root":{"componentId":"ha-cards/tile"}}}"""
      )
      .jsonObject

  private fun directory() = Files.createTempDirectory("catalog-publication").toFile()

  @Test
  fun authenticatedPinnedPublicationIsImmediatelyDiscoverableAndRecoverable() = testApplication {
    val dir = directory()
    DesignPublisher.publish(listOf(BuiltDesign(key, 1, bytes)), serial = 5, nowEpochMs = 1_000)
      .files
      .forEach { (path, data) ->
        File(dir, path).also {
          it.parentFile.mkdirs()
          it.writeBytes(data)
        }
      }
    val store = CatalogDesignStore(directory())
    val holder = LibraryHolder(dir, catalogDesigns = store)
    val compiled = AtomicInteger()
    externalServices {
      hosts("http://uib") {
        routing {
          get("/version") {
            call.respondText("""{"version":"3.144.0"}""", ContentType.Application.Json)
          }
          get("/api/ui-builder/v1/identity") {
            val actor =
              if (call.request.headers[HttpHeaders.Cookie] == "signed-admin") "github:admin"
              else "github:guest"
            call.respondText(
              """{"actorId":"$actor","signedIn":true,"canWrite":true}""",
              ContentType.Application.Json,
            )
          }
          get("/d/captured/raw") { call.respondBytes(bytes, ContentType.Application.OctetStream) }
          post("/api/ui-builder/v1/documents/native-capture") {
            assertEquals("test-operator", call.request.headers["X-Compose-Preview-Token"])
            assertEquals(source, Json.parseToJsonElement(call.receiveText()))
            compiled.incrementAndGet()
            call.respondText(
              """{"designId":"admin-draft","revision":7,"documentUrl":"/d/captured"}""",
              ContentType.Application.Json,
            )
          }
        }
      }
    }
    val http = createClient { followRedirects = false }
    val config = UiBuilderConfig("http://uib", "https://builder.example", "test-operator")
    application {
      designServer(
        holder,
        UiBuilderIntegration(config, UiBuilderClient(config, http)),
        catalogDesigns = store,
        catalogAdmins = setOf("github:admin"),
      )
    }
    val newKey = key.copy(theme = "style:brand-new:light")
    fun body(revision: Long) =
      Json.encodeToString(
        CatalogPublishRequest.serializer(),
        CatalogPublishRequest("brand-new", revision, DesignLibrary.idOf(key), newKey, source),
      )
    val denied =
      client.post("/api/designs/catalog/publish") {
        contentType(ContentType.Application.Json)
        setBody(body(0))
      }
    assertEquals(HttpStatusCode.Forbidden, denied.status)
    assertEquals(0, compiled.get())
    assertEquals(
      HttpStatusCode.OK,
      client.get("/studio/catalog") { header(HttpHeaders.Cookie, "signed-admin") }.status,
    )
    val validation =
      client.post("/api/designs/catalog/publish") {
        header(HttpHeaders.Cookie, "signed-admin")
        contentType(ContentType.Application.Json)
        val request = Json.decodeFromString(CatalogPublishRequest.serializer(), body(0))
        setBody(
          Json.encodeToString(CatalogPublishRequest.serializer(), request.copy(validateOnly = true))
        )
      }
    assertEquals(HttpStatusCode.OK, validation.status, validation.bodyAsText())
    assertTrue(store.snapshot().revisions.isEmpty())
    assertEquals(2, compiled.get())
    val first =
      client.post("/api/designs/catalog/publish") {
        header(HttpHeaders.Cookie, "signed-admin")
        contentType(ContentType.Application.Json)
        setBody(body(0))
      }
    assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
    assertEquals(4, compiled.get(), "same pinned source must reproduce identical bytes")
    assertTrue(client.get("/api/designs/catalog/styles").bodyAsText().contains("brand-new"))
    assertTrue(client.get("/api/designs").bodyAsText().contains("brand-new"))
    assertTrue(client.get("/designs/v1/manifest.json").bodyAsText().contains("brand-new"))
    assertEquals(
      source,
      Json.parseToJsonElement(client.get("/api/designs/brand-new/source.uid").bodyAsText()),
    )
    assertContentEquals(
      bytes,
      client.get("/designs/v1/blobs/${store.latest().single().entry.sha256}.rc").bodyAsBytes(),
    )
    val stale =
      client.post("/api/designs/catalog/publish") {
        header(HttpHeaders.Cookie, "signed-admin")
        contentType(ContentType.Application.Json)
        setBody(body(0))
      }
    assertEquals(HttpStatusCode.Conflict, stale.status)
    assertEquals(1, store.snapshot().revisions.size)
    val invalid =
      client.post("/api/designs/catalog/publish") {
        header(HttpHeaders.Cookie, "signed-admin")
        contentType(ContentType.Application.Json)
        setBody(body(1).replace("published-sha", "candidate"))
      }
    assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
    assertEquals(1, store.snapshot().revisions.size)
    val simpleForm =
      client.post("/api/designs/catalog/publish") {
        header(HttpHeaders.Cookie, "signed-admin")
        setBody(body(1))
      }
    assertEquals(HttpStatusCode.UnsupportedMediaType, simpleForm.status)
    val history =
      Json.parseToJsonElement(client.get("/api/designs/catalog/publications").bodyAsText())
        .jsonObject["revisions"]!!
        .jsonArray
    assertEquals(source, history.single().jsonObject["source"])
    // Replacing a default design must not make its original contract disappear for the next edit.
    for (revision in 0L..1L) {
      val replacement =
        CatalogPublishRequest("replacement", revision, DesignLibrary.idOf(key), key, source)
      val response =
        client.post("/api/designs/catalog/publish") {
          header(HttpHeaders.Cookie, "signed-admin")
          contentType(ContentType.Application.Json)
          setBody(Json.encodeToString(CatalogPublishRequest.serializer(), replacement))
        }
      assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }
    assertEquals(2, store.latest().first { it.id == "replacement" }.revision)
  }
}
