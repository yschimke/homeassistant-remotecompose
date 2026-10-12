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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
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
      "Design in UI Builder" in
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

  private fun attrs(vararg pairs: Pair<String, String>) =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
}
