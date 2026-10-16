package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignCatalogEntry
import ee.schimke.ha.design.DesignExample
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.EntityState
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Card schemas, and seeding UI Builder designs from them through a fake UI Builder. */
class UiBuilderTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = "baked:Material3:light",
    )

  private val entitiesCard = buildJsonObject {
    put("type", "entities")
    put("title", "Living room")
    put(
      "entities",
      Json.parseToJsonElement(
        """["light.kitchen", {"entity": "sensor.temp", "name": "Temperature"}]"""
      ),
    )
  }

  private fun library(): File {
    val dir = Files.createTempDirectory("designs").toFile()
    val output =
      DesignPublisher.publish(listOf(BuiltDesign(key, 1, template)), serial = 1, nowEpochMs = 1_000)
    for ((path, bytes) in output.files) File(dir, path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)
    val entry =
      DesignCatalogEntry(
        id = DesignLibrary.idOf(key),
        sha256 = output.manifest.designs.first().sha256,
        key = key,
        example =
          DesignExample(
            "fixture:tile",
            buildJsonObject {
              put("type", "tile")
              put("entity", "light.kitchen")
            },
            mapOf(
              "light.s0.name" to JsonPrimitive("Kitchen"),
              "light.s0.state" to JsonPrimitive("On"),
              "light.s0.is_on" to JsonPrimitive(true),
            ),
          ),
      )
    File(dir, DesignCatalog.PATH)
      .writeText(
        Json.encodeToString(DesignCatalog.serializer(), DesignCatalog(1, 1_000, listOf(entry)))
      )
    return dir
  }

  @Test
  fun aCardsSchemaIsItsSlotsAndTheBindingsTheAppPushes() {
    val schema =
      CardSchema.fromCard(
        entitiesCard,
        mapOf(
          "light.kitchen" to
            EntityState("light.kitchen", "on", attrs("friendly_name" to "Kitchen")),
          "sensor.temp" to EntityState("sensor.temp", "21.5", attrs("unit_of_measurement" to "°C")),
        ),
      )
    assertEquals(listOf("light.s0", "sensor.s1"), schema.slots.map { it.slot })
    assertEquals(listOf("light.kitchen", "sensor.temp"), schema.slots.map { it.entity })
    val bindings = schema.bindings.associateBy { it.name }
    assertEquals(JsonPrimitive("Living room"), bindings.getValue("card.title").example)
    assertEquals(JsonPrimitive("Kitchen"), bindings.getValue("light.s0.name").example)
    assertEquals(BindingType.Bool, bindings.getValue("light.s0.is_on").type)
    assertEquals(JsonPrimitive("Temperature"), bindings.getValue("sensor.s1.name").example)
    assertEquals(BindingType.Float, bindings.getValue("sensor.s1.numeric_state").type)
  }

  @Test
  fun aDesignsSchemaIsItsTemplatesBindingsWithItsExampleValues() {
    val library = DesignLibrary.load(library())
    val schema = CardSchema.fromDesign(library, library.designs.single())
    assertEquals(mapOf("domain" to "light"), schema.properties)
    assertEquals(listOf("light.s0"), schema.slots.map { it.slot })
    assertEquals("light.kitchen", schema.slots.single().entity)
    val bindings = schema.bindings.associateBy { it.name }
    // Decoded as an int from the template; a boolean to a designer.
    assertEquals(BindingType.Bool, bindings.getValue("light.s0.is_on").type)
    assertEquals(JsonPrimitive("Kitchen"), bindings.getValue("light.s0.name").example)
  }

  @Test
  fun theSeededDesignReadsEveryBindingThroughStateVariables() {
    val schema = CardSchema.fromCard(entitiesCard)
    val document = UiBuilderSeed.document(schema, "card-entities-1", "entities")
    assertEquals(
      "remote-m3",
      document["catalogPin"]!!.jsonObject["systemId"]!!.jsonPrimitive.content,
    )
    val state = document["stateVariables"]!!.jsonObject
    assertEquals(schema.bindings.map { it.name }.toSet(), state.keys)
    assertEquals(
      "bool",
      state.getValue("light.s0.is_on").jsonObject["valueType"]!!.jsonPrimitive.content,
    )

    val nodes = document["nodes"]!!.jsonObject
    // Every state reference names a declared variable.
    val referenced =
      nodes.values.flatMap { node ->
        node.jsonObject["properties"]!!.jsonObject.values.mapNotNull { value ->
          value.jsonObject
            .takeIf { it["type"]?.jsonPrimitive?.content == "state" }
            ?.get("variable")
            ?.jsonPrimitive
            ?.content
        }
      }
    assertTrue(referenced.isNotEmpty())
    assertTrue(state.keys.containsAll(referenced))
    // A light toggles; a sensor doesn't.
    assertNotNull(nodes["switch0"])
    assertFalse("switch1" in nodes)
    assertEquals(
      "card.title",
      nodes["title"]!!
        .jsonObject["properties"]!!
        .jsonObject["text"]!!
        .jsonObject["variable"]!!
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun aSwitchWithNoStateTextHasNoLabel() {
    // A template that binds `is_on` and `name` but no `state` text: never label with the boolean,
    // and don't repeat the name the row already shows.
    val schema =
      CardSchema(
        cardType = "entities",
        source = "test",
        slots = listOf(SlotSchema(0, "light.s0", "light")),
        bindings =
          listOf(
            BindingSchema("light.s0.is_on", BindingType.Bool, JsonPrimitive(true)),
            BindingSchema("light.s0.name", BindingType.Text, JsonPrimitive("Kitchen")),
          ),
      )
    val nodes = UiBuilderSeed.document(schema, "d", "t")["nodes"]!!.jsonObject
    assertFalse("switch-label0" in nodes)
    assertEquals(0, nodes["switch0"]!!.jsonObject["slots"]!!.jsonObject["label"]!!.jsonArray.size)
  }

  @Test
  fun schemaApis() = testApplication {
    application { designServer(LibraryHolder(library())) }
    val id = DesignLibrary.idOf(key)
    val design =
      Json.parseToJsonElement(client.get("/api/designs/$id/schema").bodyAsText()).jsonObject
    assertEquals("design:$id", design["source"]!!.jsonPrimitive.content)

    val card = client.post("/api/schema") { setBody(entitiesCard.toString()) }
    assertEquals(HttpStatusCode.OK, card.status)
    assertEquals(2, Json.parseToJsonElement(card.bodyAsText()).jsonObject["slots"]!!.jsonArray.size)
    assertEquals(
      HttpStatusCode.BadRequest,
      client.post("/api/schema") { setBody("""{"no":"type"}""") }.status,
    )

    val page =
      client
        .submitForm("/schema", parameters { append("card", entitiesCard.toString()) })
        .bodyAsText()
    assertTrue("sensor.s1.name" in page)
    assertTrue("Run the server with UI Builder" in page, "no UI Builder configured")
  }

  @Test
  fun aLibraryDesignHasAnEditableSourceInItsTheme() = testApplication {
    application { designServer(LibraryHolder(library())) }
    val id = DesignLibrary.idOf(key)
    val response = client.get("/api/designs/$id/source.uid")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(
      "attachment; filename=$id.uid",
      response.headers[HttpHeaders.ContentDisposition],
    )
    val source = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    assertEquals("ha-cards", source["catalogPin"]!!.jsonObject["systemId"]!!.jsonPrimitive.content)
    assertFalse("template" in source, "a design, not a starting template")
    val nodes = source["nodes"]!!.jsonObject
    fun JsonObject.child(slot: String) = this["slots"]!!.jsonObject[slot]!!.jsonArray.single()
    // Root → the design's uber theme → the card, named by slot so it binds light.s0.*.
    val theme = nodes.getValue("root").jsonObject.child("content").jsonPrimitive.content
    val themeNode = nodes.getValue(theme).jsonObject
    assertEquals("ha-cards/uber-theme", themeNode["componentId"]!!.jsonPrimitive.content)
    val themeProps = themeNode["properties"]!!.jsonObject
    assertEquals("classic", themeProps["theme"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    assertFalse(themeProps["dark"]!!.jsonObject["value"]!!.jsonPrimitive.boolean)
    val tile = nodes.getValue(themeNode.child("children").jsonPrimitive.content).jsonObject
    val props = tile["properties"]!!.jsonObject
    assertEquals("light.s0", props["entityId"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    assertEquals("Kitchen", props["name"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    // Every binding the template has is a field to bind.
    assertTrue("light.s0.is_on" in source["stateVariables"]!!.jsonObject)
    assertTrue(
      "/api/designs/$id/source.uid" in client.get("/api/designs/$id").bodyAsText(),
      "the design's JSON links its source",
    )
    assertEquals(HttpStatusCode.NotFound, client.get("/api/designs/nope/source.uid").status)
  }

  @Test
  fun aDesignsUberThemeComesFromItsKey() {
    fun theme(value: String) = HaCardsSeed.uberThemeOf(key.copy(theme = value))
    assertEquals(HaCardsSeed.UberTheme("lcd", dark = true), theme("style:lcd:dark"))
    assertEquals(HaCardsSeed.UberTheme("braun", dark = false), theme("style:braun:light"))
    assertEquals(HaCardsSeed.UberTheme("classic", dark = true), theme("baked:TerrazzoHome:dark"))
    assertEquals(null, theme("custom"))
  }

  @Test
  fun seedsAUiBuilderDesignForAnAuthorizedBrowser() = testApplication {
    var created: JsonObject? = null
    var precondition: String? = null
    externalServices {
      hosts("http://uib") {
        routing {
          put("/api/ui-builder/v1/designs/{id}") {
            precondition = call.request.header(HttpHeaders.IfNoneMatch)
            created = Json.parseToJsonElement(call.receiveText()).jsonObject
            call.respondText("created", status = HttpStatusCode.Created)
          }
          studioCatalogFixture(token = "secret")
        }
      }
    }
    val config =
      UiBuilderConfig(
        apiUrl = "http://uib",
        browserUrl = "https://builder.example",
        token = "secret",
      )
    val uiBuilder = UiBuilderIntegration(config, UiBuilderClient(config, createClient {}))
    application { designServer(LibraryHolder(library()), uiBuilder) }
    val browser = createClient { followRedirects = false }
    val id = DesignLibrary.idOf(key)

    // Without the edit cookie: offered, not usable.
    assertTrue("open the edit link" in browser.get("/designs/$id").bodyAsText())
    assertEquals(
      HttpStatusCode.Forbidden,
      browser.submitForm("/ui-builder/designs", parameters { append("design", id) }).status,
    )
    assertEquals(HttpStatusCode.Forbidden, browser.get("/edit?token=wrong").status)

    val edit = browser.get("/edit?token=secret")
    val cookie = assertNotNull(edit.headers[HttpHeaders.SetCookie]).substringBefore(';')
    assertTrue(
      "Fork in UI Builder" in
        browser.get("/designs/$id") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
    )

    val response =
      browser.submitForm("/ui-builder/designs", parameters { append("design", id) }) {
        header(HttpHeaders.Cookie, cookie)
      }
    assertEquals(HttpStatusCode.Found, response.status)
    val document = assertNotNull(created)
    val designId = document["id"]!!.jsonPrimitive.content
    assertEquals(
      "https://builder.example/ui-builder/$designId?token=secret",
      response.headers[HttpHeaders.Location],
    )
    assertEquals("*", precondition, "creates, never replaces")
    // A fork of the design's source, pinned to the served ha-cards catalog.
    assertEquals(
      "sha256:published",
      document["catalogPin"]!!.jsonObject["catalogRevision"]!!.jsonPrimitive.content,
    )
    assertEquals(
      "ha-cards/tile",
      document["nodes"]!!.jsonObject["tile"]!!.jsonObject["componentId"]!!.jsonPrimitive.content,
    )
    val state = document["stateVariables"]!!.jsonObject
    assertTrue(state.getValue("light.s0.is_on").jsonObject["initialValue"]!!.jsonPrimitive.boolean)
    assertEquals(
      "Kitchen",
      state.getValue("light.s0.name").jsonObject["initialValue"]!!.jsonPrimitive.content,
    )

    // From a card's JSON too.
    val fromCard =
      browser.submitForm(
        "/ui-builder/designs",
        parameters { append("card", entitiesCard.toString()) },
      ) {
        header(HttpHeaders.Cookie, cookie)
      }
    assertEquals(HttpStatusCode.Found, fromCard.status)
    assertTrue("sensor.s1.name" in created!!["stateVariables"]!!.jsonObject)
  }

  @Test
  fun aPastedCardTakesItsCardSpecificBindingsFromTheClosestTemplate() {
    val library = DesignLibrary.load(library())
    val tile = buildJsonObject {
      put("type", "tile")
      put("entity", "light.hall")
    }
    val schema = CardSchema.fromCard(tile, library = library)
    assertEquals(DesignLibrary.idOf(key), schema.template)
    // Every binding the template declares is in the schema, with the template example's value
    // where the card itself has none.
    val names = schema.bindings.map { it.name }.toSet()
    assertTrue(library.decode(library.designs.single()).bindings.all { it.name in names })

    // No template of the type: said, not silently incomplete.
    val unknown = CardSchema.fromCard(entitiesCard, library = library)
    assertEquals(null, unknown.template)
    assertTrue(unknown.notes.single().startsWith("No library template of `entities`"))

    // A tile with other slots: nothing borrowed from a template whose slots differ.
    val sensorTile = buildJsonObject {
      put("type", "tile")
      put("entity", "sensor.temp")
    }
    val other = CardSchema.fromCard(sensorTile, library = library)
    assertEquals(null, other.template)
    assertTrue(other.bindings.none { it.name.startsWith("light.") })
  }

  @Test
  fun colourAndImageBindingsAreReportedNotDropped() {
    val schema =
      CardSchema(
        cardType = "picture-entity",
        source = "test",
        bindings =
          listOf(
            BindingSchema("camera.s0.image", BindingType.Image),
            BindingSchema("camera.s0.name", BindingType.Text, JsonPrimitive("Door")),
          ),
      )
    assertEquals(listOf("camera.s0.image"), UiBuilderSeed.unsupported(schema).map { it.name })
    val state = UiBuilderSeed.document(schema, "d", "t")["stateVariables"]!!.jsonObject
    assertEquals(setOf("camera.s0.name"), state.keys)
  }

  @Test
  fun theBundledBuilderIsReachedWhereItIsBound() {
    assertEquals("127.0.0.1", UiBuilderConfig.connectHost("0.0.0.0"))
    assertEquals("[::1]", UiBuilderConfig.connectHost("::"))
    assertEquals("10.0.0.5", UiBuilderConfig.connectHost("10.0.0.5"))
    assertEquals("[fd00::5]", UiBuilderConfig.connectHost("fd00::5"))
    val (config, _) =
      UiBuilderConfig.fromEnvironment(
        mapOf("UI_BUILDER_BINARY" to "/bin/true", "UI_BUILDER_HOST" to "10.0.0.5")
      )!!
    assertEquals("http://10.0.0.5:8724", config.apiUrl)
  }

  @Test
  fun aLoadedLibraryServesItsBlobsAfterTheDirectoryIsReplaced() {
    val dir = library()
    val loaded = DesignLibrary.load(dir)
    File(dir, "blobs").deleteRecursively()
    assertNotNull(loaded.blob(loaded.designs.single().entry.sha256))
  }

  @Test
  fun githubSignInSeedsADesignSharedWithItsDesigner() = testApplication {
    var shared: Map<String, String>? = null
    externalServices {
      hosts("http://uib") {
        routing {
          put("/api/ui-builder/v1/designs/{id}") {
            call.respondText("created", status = HttpStatusCode.Created)
          }
          studioCatalogFixture(token = "secret")
          post("/ui-builder/{id}/access") {
            assertEquals("secret", call.request.queryParameters["token"])
            shared = call.receiveParameters().entries().associate { it.key to it.value.single() }
            call.response.headers.append(HttpHeaders.Location, "/ui-builder/x/access")
            call.respond(HttpStatusCode.SeeOther)
          }
        }
      }
      hosts("http://gh") {
        routing {
          post("/token") {
            val form = call.receiveParameters()
            assertEquals("the-code", form["code"])
            assertEquals("client-secret", form["client_secret"])
            call.respondText("""{"access_token":"gho_x"}""", ContentType.Application.Json)
          }
          get("/user") {
            assertEquals("Bearer gho_x", call.request.headers[HttpHeaders.Authorization])
            call.respondText("""{"login":"octocat"}""", ContentType.Application.Json)
          }
        }
      }
    }
    val http = createClient { followRedirects = false }
    val config =
      UiBuilderConfig(
        apiUrl = "http://uib",
        browserUrl = "https://builder.example",
        token = "secret",
      )
    val github =
      GithubAuth(
        clientId = "client-id",
        clientSecret = "client-secret",
        publicUrl = "https://ha.example",
        sessionSecret = "session-secret",
        http = http,
        tokenUrl = "http://gh/token",
        userUrl = "http://gh/user",
      )
    application {
      designServer(
        LibraryHolder(library()),
        UiBuilderIntegration(config, UiBuilderClient(config, http)),
        github,
      )
    }
    val browser = createClient { followRedirects = false }
    val id = DesignLibrary.idOf(key)

    // Signed out: offered sign-in, refused editing, and no shared edit link.
    assertTrue("Sign in with GitHub" in browser.get("/designs/$id").bodyAsText())
    assertEquals(
      HttpStatusCode.Forbidden,
      browser.submitForm("/ui-builder/designs", parameters { append("design", id) }).status,
    )
    assertEquals(HttpStatusCode.NotFound, browser.get("/edit?token=secret").status)

    // Sign in: GitHub's authorize page, back with the state this browser was given.
    val start = browser.get(GithubAuth.START_PATH + "?return=/designs/$id")
    val authorize = assertNotNull(start.headers[HttpHeaders.Location])
    assertTrue(authorize.startsWith("https://github.com/login/oauth/authorize?client_id=client-id"))
    val state = authorize.substringAfter("state=").substringBefore('&')
    val stateCookie = cookieOf(start)
    // A callback this browser didn't start is refused.
    assertEquals(
      HttpStatusCode.BadRequest,
      browser.get("${GithubAuth.CALLBACK_PATH}?code=the-code&state=$state").status,
    )
    val callback =
      browser.get("${GithubAuth.CALLBACK_PATH}?code=the-code&state=$state") {
        header(HttpHeaders.Cookie, stateCookie)
      }
    assertEquals("/designs/$id", callback.headers[HttpHeaders.Location])
    val session =
      callback.headers
        .getAll(HttpHeaders.SetCookie)!!
        .first { it.startsWith("design-server-session=") }
        .substringBefore(';')
    assertTrue(
      "as <b>octocat</b>" in
        browser.get("/designs/$id") { header(HttpHeaders.Cookie, session) }.bodyAsText()
    )

    // Seeded, then shared with the designer as an editor; the editor link carries no token.
    val seeded =
      browser.submitForm("/ui-builder/designs", parameters { append("design", id) }) {
        header(HttpHeaders.Cookie, session)
      }
    assertEquals(HttpStatusCode.Found, seeded.status)
    val location = assertNotNull(seeded.headers[HttpHeaders.Location])
    assertTrue(location.startsWith("https://builder.example/ui-builder/card-tile-"), location)
    assertFalse("token" in location)
    assertEquals(mapOf("actorId" to "github:octocat", "role" to "editor"), shared)

    // A tampered session is nobody.
    val forged = session.dropLast(2) + "xx"
    assertTrue(
      "Sign in with GitHub" in
        browser.get("/designs/$id") { header(HttpHeaders.Cookie, forged) }.bodyAsText()
    )
  }

  @Test
  fun theBundledBuilderSignsPeopleInWithGithubToo() {
    assertEquals(emptyList(), UiBuilderConfig.githubArgs(emptyMap()))
    val args =
      UiBuilderConfig.githubArgs(
        mapOf(
          "UI_BUILDER_GITHUB_CLIENT_ID" to "id",
          "UI_BUILDER_GITHUB_CLIENT_SECRET" to "secret",
          "UI_BUILDER_COOKIE_SECRET" to "cookie",
          "UI_BUILDER_BROWSER_URL" to "https://builder.ha.coo.ee",
        )
      )
    assertEquals(
      "https://builder.ha.coo.ee",
      args[args.indexOf("--github-auth-callback-base-url") + 1],
    )
    assertTrue("--github-auth-open-ui-builder" in args)
    assertEquals("private", args[args.indexOf("--ui-builder-default-visibility") + 1])
  }

  @Test
  fun signInOnlyReturnsToAPathOnThisSite() {
    assertEquals("/designs/x", GithubAuth.safeReturn("/designs/x"))
    assertEquals("/", GithubAuth.safeReturn("https://evil.example/"))
    assertEquals("/", GithubAuth.safeReturn("//evil.example/"))
    assertEquals("/", GithubAuth.safeReturn(null))
  }

  private fun cookieOf(response: io.ktor.client.statement.HttpResponse): String =
    assertNotNull(response.headers[HttpHeaders.SetCookie]).substringBefore(';')

  private fun attrs(vararg pairs: Pair<String, String>) =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
}
