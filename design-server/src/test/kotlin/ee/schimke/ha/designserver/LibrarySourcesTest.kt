package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The library's card designs as UI Builder designs (server-owned-card-designs.md): edited in place
 * by the library's editors, read back by the scheduled import, and forked from the real source.
 */
class LibrarySourcesTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = "baked:Material3:light",
    )

  private val checkedIn =
    """
    {
      "schema": "compose-ui-builder-document/v1-candidate",
      "id": "tile",
      "title": "Tile",
      "revision": 0,
      "catalogPin": {"systemId": "ha-cards", "catalogRevision": "candidate",
        "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
      "environment": {"widthDp": 360, "heightDp": 72, "density": 2, "theme": "light",
        "locale": "en-US", "fontScale": 1, "layoutDirection": "ltr"},
      "stateVariables": {},
      "roots": ["root"],
      "nodes": {
        "root": {"id": "root", "componentId": "ha-cards/dashboard-card", "properties": {},
          "modifiers": [], "slots": {"content": ["tile"]}},
        "tile": {"id": "tile", "componentId": "ha-cards/entity-tile", "properties": {},
          "modifiers": [], "slots": {}}
      }
    }
    """
      .trimIndent()

  private fun library(): File {
    val dir = Files.createTempDirectory("designs").toFile()
    val output =
      DesignPublisher.publish(listOf(BuiltDesign(key, 1, template)), serial = 1, nowEpochMs = 1_000)
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    File(dir, "${DesignLibrary.SOURCES}/tile.uid")
      .also { it.parentFile.mkdirs() }
      .writeText(checkedIn)
    return dir
  }

  /** A UI Builder that holds [stored] designs by id and records what it is asked to create. */
  private class FakeUiBuilder(val stored: MutableMap<String, JsonObject> = mutableMapOf()) {
    val created = mutableListOf<JsonObject>()
  }

  private fun io.ktor.server.testing.ApplicationTestBuilder.uiBuilder(
    fake: FakeUiBuilder
  ): UiBuilderIntegration {
    externalServices {
      hosts("http://uib") {
        routing {
          put("/api/ui-builder/v1/designs/{id}") {
            val document = Json.parseToJsonElement(call.receiveText()).jsonObject
            fake.created += document
            fake.stored[call.parameters["id"]!!] = document
            call.respondText("created", status = HttpStatusCode.Created)
          }
          get("/api/ui-builder/v1/designs/{id}") {
            val document =
              fake.stored[call.parameters["id"]!!]
                ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
            call.respondText(Json.encodeToString(JsonObject.serializer(), document))
          }
          studioCatalogFixture(token = "secret")
        }
      }
    }
    val config =
      UiBuilderConfig(apiUrl = "http://uib", browserUrl = "https://builder.example", "secret")
    return UiBuilderIntegration(config, UiBuilderClient(config, createClient {}))
  }

  @Test
  fun theImportReadsTheCheckedInSourceUntilTheLibraryDesignIsEdited() = testApplication {
    val fake = FakeUiBuilder()
    val integration = uiBuilder(fake)
    application { designServer(LibraryHolder(library()), integration) }

    val before = Json.parseToJsonElement(client.get("/api/library/sources").bodyAsText())
    val source = before.jsonObject["sources"]!!.jsonArray.single().jsonObject
    assertEquals("tile", source["cardType"]!!.jsonPrimitive.content)
    assertFalse(source["edited"]!!.jsonPrimitive.boolean)
    assertEquals(
      Json.parseToJsonElement(checkedIn),
      source["document"],
      "the checked-in source, as it is",
    )

    // Edited in UI Builder at revision 7: the import reads that, named for its file.
    val edited =
      Json.parseToJsonElement(checkedIn).jsonObject.let {
        JsonObject(
          it +
            mapOf(
              "id" to kotlinx.serialization.json.JsonPrimitive("library-tile"),
              "revision" to kotlinx.serialization.json.JsonPrimitive(7),
            )
        )
      }
    fake.stored["library-tile"] = edited
    val one = Json.parseToJsonElement(client.get("/api/library/sources/tile.uid").bodyAsText())
    assertEquals("tile", one.jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals(7, one.jsonObject["revision"]!!.jsonPrimitive.content.toInt())
    assertEquals(HttpStatusCode.NotFound, client.get("/api/library/sources/gauge.uid").status)
  }

  @Test
  fun onlyTheLibrarysEditorsMayEditItAndTheFirstEditCreatesItFromTheSource() = testApplication {
    val fake = FakeUiBuilder()
    val integration = uiBuilder(fake)
    application { designServer(LibraryHolder(library()), integration) }
    val browser = createClient { followRedirects = false }
    val id = DesignLibrary.idOf(key)

    assertEquals(HttpStatusCode.Forbidden, browser.submitForm("/library/tile/edit").status)
    val cookie =
      assertNotNull(browser.get("/edit?token=secret").headers[HttpHeaders.SetCookie])
        .substringBefore(';')
    assertTrue(
      "Edit the library design" in
        browser.get("/designs/$id") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
    )

    val first = browser.submitForm("/library/tile/edit") { header(HttpHeaders.Cookie, cookie) }
    assertEquals(HttpStatusCode.Found, first.status)
    assertEquals(
      "https://builder.example/ui-builder/library-tile?token=secret",
      first.headers[HttpHeaders.Location],
    )
    val created = fake.created.single()
    assertEquals("library-tile", created["id"]!!.jsonPrimitive.content)
    assertEquals("0", created["revision"]!!.jsonPrimitive.content)
    assertEquals(
      "ha-cards/entity-tile",
      created["nodes"]!!.jsonObject["tile"]!!.jsonObject["componentId"]!!.jsonPrimitive.content,
      "the real design, not a reconstruction",
    )

    // A second edit opens the same design: nothing new is created.
    browser.submitForm("/library/tile/edit") { header(HttpHeaders.Cookie, cookie) }
    assertEquals(1, fake.created.size)
    assertEquals(
      HttpStatusCode.NotFound,
      browser.submitForm("/library/gauge/edit") { header(HttpHeaders.Cookie, cookie) }.status,
    )
  }

  @Test
  fun aForkOfAMigratedCardTypeStartsFromItsRealDesign() = testApplication {
    val fake = FakeUiBuilder()
    val integration = uiBuilder(fake)
    application { designServer(LibraryHolder(library()), integration) }
    val browser = createClient { followRedirects = false }
    val id = DesignLibrary.idOf(key)
    val cookie =
      assertNotNull(browser.get("/edit?token=secret").headers[HttpHeaders.SetCookie])
        .substringBefore(';')

    val source = Json.parseToJsonElement(browser.get("/api/designs/$id/source.uid").bodyAsText())
    assertEquals(
      "ha-cards/entity-tile",
      source.jsonObject["nodes"]!!
        .jsonObject["tile"]!!
        .jsonObject["componentId"]!!
        .jsonPrimitive
        .content,
    )

    browser.submitForm("/ui-builder/designs", parameters { append("design", id) }) {
      header(HttpHeaders.Cookie, cookie)
    }
    val fork = fake.created.single()
    assertTrue(fork["id"]!!.jsonPrimitive.content.startsWith("card-tile-"))
    assertEquals("tile (fork)", fork["title"]!!.jsonPrimitive.content)
    assertEquals(
      "ha-cards/entity-tile",
      fork["nodes"]!!.jsonObject["tile"]!!.jsonObject["componentId"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun withGitHubSignInOnlyNamedLoginsAreLibraryEditors() {
    val sources = LibrarySources(LibraryHolder(library()), uiBuilder = null, setOf("Yuri"))
    assertTrue(sources.mayEdit(EditorState(enabled = true, authorized = true, login = "yuri")))
    assertFalse(sources.mayEdit(EditorState(enabled = true, authorized = true, login = "someone")))
    assertFalse(sources.mayEdit(EditorState(enabled = true, authorized = false, login = "yuri")))
    // The shared edit link (no GitHub sign-in) is the operator.
    assertTrue(sources.mayEdit(EditorState(enabled = true, authorized = true)))
  }
}
