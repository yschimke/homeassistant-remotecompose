package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.components.ThemeStyle
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image

/**
 * The tools an agent learns and looks with in a card or dashboard session: the guides, the draft
 * check, and the renders with the card's current values or sample ones.
 */
class AgentGuideTest {
  /** An empty published library: designs are captured on request. */
  private fun libraryDir(): File {
    val dir = Files.createTempDirectory("agent-guide-library").toFile()
    DesignPublisher.publish(emptyList(), serial = 1, nowEpochMs = 1_000).files.forEach {
      (path, bytes) ->
      File(dir, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }
    return dir
  }

  private val hall = buildJsonObject {
    put("type", "tile")
    put("entity", "light.hall")
  }
  private val hallStates =
    mapOf(
      "light.hall" to
        EntityState(
          "light.hall",
          "off",
          buildJsonObject { put("friendly_name", "Hall") },
        ),
    )

  /** The key the app plays the hall tile with, as it computes it. */
  private val hallKey: DesignKey =
    requireNotNull(
      CardDesignTemplates.keyFor(
        CardConfig("tile", hall),
        HaSnapshot(states = hallStates),
        ThemeStyle.entries.first(),
        dark = false,
      ),
    )

  private var id = 0

  private suspend fun ApplicationTestBuilder.rpc(
    method: String,
    params: JsonObject? = null,
  ): JsonObject {
    val response =
      client.post("/api/agent/mcp") {
        setBody(
          buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ++id)
            put("method", method)
            params?.let { put("params", it) }
          }
            .toString(),
        )
      }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result").jsonObject
  }

  private suspend fun ApplicationTestBuilder.mcp(tool: String, args: JsonObject): JsonObject =
    rpc(
      "tools/call",
      buildJsonObject {
        put("name", tool)
        put("arguments", args)
      },
    )

  private fun JsonObject.data() = getValue("structuredContent").jsonObject

  private fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.boolean == true

  /** The PNGs a tool returned as MCP image content. */
  private fun JsonObject.images(): List<ByteArray> =
    getValue("content")
      .jsonArray
      .map { it.jsonObject }
      .filter { it["type"]?.jsonPrimitive?.content == "image" }
      .map {
        assertEquals("image/png", it.getValue("mimeType").jsonPrimitive.content)
        Base64.getDecoder().decode(it.getValue("data").jsonPrimitive.content)
      }

  private fun JsonObject.captions(): List<String> =
    getValue("content")
      .jsonArray
      .map { it.jsonObject }
      .filter { it["type"]?.jsonPrimitive?.content == "text" }
      .map { it.getValue("text").jsonPrimitive.content }

  /** A rendered PNG within the limits an agent is shown, kept under build/ to look at. */
  private fun check(png: ByteArray, name: String) {
    assertTrue(png.size in 1..1_500_000, "$name is ${png.size} bytes")
    val image = Image.makeFromEncoded(png)
    assertTrue(
      image.width in 1..1800 && image.height in 1..1800,
      "$name is ${image.width}×${image.height}",
    )
    File("build/agent-renders").apply { mkdirs() }.resolve("$name.png").writeBytes(png)
  }

  private val toolNames: (JsonObject) -> List<String> = { list ->
    list.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
  }

  @Test
  fun aCardAgentLearnsChecksAndSeesItsDraftWithCurrentAndSampleValues() = testApplication {
    externalServices { hosts("https://builder.test") { routing { studioCatalogFixture() } } }
    val config = UiBuilderConfig("https://builder.test", "https://builder.test", "operator")
    val http = createClient { followRedirects = false }
    application {
      designServer(
        LibraryHolder(libraryDir()),
        UiBuilderIntegration(config, UiBuilderClient(config, http)),
      )
    }
    // The app seeds the card with its current values and opens a session for it.
    val seed =
      client.post("/api/studio/seed") {
        setBody(
          buildJsonObject {
            put("card", hall)
            putJsonObject("states") {
              putJsonObject("light.hall") {
                put("state", "off")
                putJsonObject("attributes") { put("friendly_name", "Hall") }
              }
            }
          }
            .toString(),
        )
      }
    assertEquals(HttpStatusCode.OK, seed.status, seed.bodyAsText())
    val document = Json.parseToJsonElement(seed.bodyAsText()).jsonObject
    val created =
      client.post("/api/studio/agent-sessions") {
        setBody(
          buildJsonObject {
            put("instruction", "Make it friendlier")
            put("document", document)
          }
            .toString(),
        )
      }
    assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
    val access = Json.parseToJsonElement(created.bodyAsText()).jsonObject
    val owner = access.getValue("ownerToken").jsonPrimitive.content
    val code = access.getValue("joinCode").jsonPrimitive.content
    val session =
      mcp("join_session", buildJsonObject { put("code", code) })
        .data()
        .getValue("session")
        .jsonPrimitive
        .content

    val tools = toolNames(rpc("tools/list"))
    for (tool in
      listOf(
        "get_card_guide",
        "check_card_draft",
        "render_card",
        "get_dashboard_guide",
        "render_dashboard",
      )) assertTrue(tool in tools, "$tool in $tools")

    // Before the app has previewed the draft there's no key, and the library has no tile design.
    val early = mcp("render_card", buildJsonObject { put("session", session) })
    assertTrue(early.isError(), early.toString())
    assertContains(early.data().getValue("error").jsonPrimitive.content, "design key")

    val guide = mcp("get_card_guide", buildJsonObject { put("session", session) }).data()
    assertEquals("tile", guide.getValue("cardType").jsonPrimitive.content)
    val components =
      guide.getValue("components").jsonArray.map {
        it.jsonObject.getValue("id").jsonPrimitive.content
      }
    assertTrue("ha-cards/remote-text" in components && "layout/collapsible-row" in components)
    assertTrue(
      guide.getValue("expressions").jsonArray.any {
        it.jsonObject["op"]?.jsonPrimitive?.content == "select" &&
          it.jsonObject["arity"]?.jsonPrimitive?.int == 3
      },
    )
    assertTrue(guide.getValue("sharedValues").jsonArray.isNotEmpty())
    val bindings =
      guide.getValue("bindings").jsonArray.associate {
        it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject
      }
    val name = bindings.getValue("light.s0.name")
    assertEquals("Hall", name.getValue("current").jsonPrimitive.content)
    assertEquals("Kitchen light", name.getValue("sample").jsonPrimitive.content)
    val isOn = bindings.getValue("light.s0.is_on")
    assertEquals(false, isOn.getValue("current").jsonPrimitive.boolean)
    assertEquals(true, isOn.getValue("sample").jsonPrimitive.boolean)
    assertEquals("On", bindings.getValue("light.s0.state").getValue("sample").jsonPrimitive.content)

    // A draft that changes the contract, or uses what isn't drawn, is flagged without submitting.
    val bad =
      JsonObject(
        document +
          ("roots" to JsonArray(listOf(JsonPrimitive("elsewhere")))) +
          ("nodes" to
            JsonObject(
              document.getValue("nodes").jsonObject +
                ("elsewhere" to
                  buildJsonObject {
                    put("id", "elsewhere")
                    put("componentId", "ha-cards/remote-slider")
                  }),
            )),
      )
    val flagged =
      mcp(
          "check_card_draft",
          buildJsonObject {
            put("session", session)
            put("document", bad)
          },
        )
        .data()
    assertEquals(false, flagged.getValue("ok").jsonPrimitive.boolean)
    val problems = flagged.getValue("problems").jsonArray.map { it.jsonPrimitive.content }
    assertTrue(problems.any { "roots" in it }, problems.toString())
    assertTrue(problems.any { "remote-slider" in it }, problems.toString())
    val fine =
      mcp(
          "check_card_draft",
          buildJsonObject {
            put("session", session)
            put("document", document)
          },
        )
        .data()
    assertEquals(true, fine.getValue("ok").jsonPrimitive.boolean, fine.toString())

    // The app previews the draft with its key; the agent's renders then draw with it.
    val preview =
      client.post("/api/studio/agent-session/preview") {
        header(HttpHeaders.Authorization, "Bearer $owner")
        setBody(
          buildJsonObject {
            put("key", Json.encodeToJsonElement(DesignKey.serializer(), hallKey))
            put("revision", 0)
          }
            .toString(),
        )
      }
    assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())

    val current = mcp("render_card", buildJsonObject { put("session", session) })
    assertFalse(current.isError(), current.toString())
    assertEquals("current", current.data().getValue("data").jsonPrimitive.content)
    assertEquals("previewed", current.data().getValue("keyFrom").jsonPrimitive.content)
    assertEquals(
      "Hall",
      current.data().getValue("values").jsonObject.getValue("light.s0.name").jsonPrimitive.content,
    )
    val currentPng = current.images().single()
    assertContains(current.captions().first(), "Revision 0 with the card's current values")
    check(currentPng, "card-current")

    val sample =
      mcp(
        "render_card",
        buildJsonObject {
          put("session", session)
          put("data", "sample")
        },
      )
    assertFalse(sample.isError(), sample.toString())
    assertEquals(
      "Kitchen light",
      sample.data().getValue("values").jsonObject.getValue("light.s0.name").jsonPrimitive.content,
    )
    val samplePng = sample.images().single()
    assertContains(sample.captions().first(), "sample values")
    check(samplePng, "card-sample")
    assertFalse(currentPng.contentEquals(samplePng), "the sample values are drawn")

    val dark =
      mcp(
        "render_card",
        buildJsonObject {
          put("session", session)
          put("dark", true)
        },
      )
    assertFalse(dark.isError(), dark.toString())
    assertTrue(dark.data().getValue("theme").jsonPrimitive.content.endsWith(":dark"))
    check(dark.images().single(), "card-current-dark")

    // A draft the compiler refuses is a failed render, naming why.
    val refused =
      mcp(
        "render_card",
        buildJsonObject {
          put("session", session)
          put("document", bad)
        },
      )
    assertTrue(refused.isError())
    assertContains(refused.data().getValue("error").jsonPrimitive.content, "remote-slider")
  }

  private val sections =
    Json.parseToJsonElement(
        """
        {"views": [{"title": "Home", "type": "sections", "sections": [
          {"type": "grid", "cards": [
            {"type": "heading", "heading": "Living room"},
            {"type": "tile", "entity": "light.living_room"},
            {"type": "tile", "entity": "sensor.living_room_temperature"},
            {"type": "entities", "entities": ["binary_sensor.front_door", "switch.coffee_machine"]}
          ]},
          {"type": "grid", "cards": [
            {"type": "tile", "entity": "cover.garage_door"},
            {"type": "custom:mushroom-light-card", "entity": "light.living_room"},
            {"entity": "light.porch"}
          ]}
        ]}]}
        """,
      )
      .jsonObject

  @Test
  fun aDashboardAgentLearnsTheCardTypesAndSeesItsDraftWithSampleStates() = testApplication {
    val guide = AgentGuide(LibraryHolder(libraryDir()))
    val relay = AgentRelay(AgentDesignSessions(), DashboardAgentSessions(), guide = guide)
    application { routing { agentRelayRoutes(relay) } }
    val access = relay.dashboards.create("Tidy the living room", sections)
    val code = relay.register(AgentRelay.Kind.Dashboard, access.owner, access.agent, "Tidy")!!
    val session =
      mcp("join_session", buildJsonObject { put("code", code) })
        .data()
        .getValue("session")
        .jsonPrimitive
        .content

    val read = mcp("get_dashboard_guide", buildJsonObject { put("session", session) }).data()
    val types = read.getValue("cardTypes").jsonArray.map { it.jsonPrimitive.content }
    for (type in listOf("tile", "entities", "heading", "thermostat", "vertical-stack")) assertTrue(
      type in types,
      "$type in $types",
    )
    assertFalse("iframe" in types, "a placeholder type isn't offered")
    assertTrue(read.containsKey("designs"))
    assertContains(read.getValue("rules").toString(), "get_render")

    // A card tool isn't a dashboard session's.
    assertTrue(mcp("get_card_guide", buildJsonObject { put("session", session) }).isError())

    val rendered = mcp("render_dashboard", buildJsonObject { put("session", session) })
    assertFalse(rendered.isError(), rendered.toString())
    val data = rendered.data()
    assertEquals("sample", data.getValue("data").jsonPrimitive.content)
    val states = data.getValue("sampleStates").jsonObject
    assertEquals("on", states.getValue("light.living_room").jsonPrimitive.content)
    assertEquals("21.5", states.getValue("sensor.living_room_temperature").jsonPrimitive.content)
    assertEquals("open", states.getValue("cover.garage_door").jsonPrimitive.content)
    assertTrue("light.porch" in states, "every entity named gets a state")
    val drawn =
      data.getValue("cards").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
    assertTrue("views[0].sections[0].cards[1]" in drawn, drawn.toString())
    assertTrue("views[0].sections[1].cards[0]" in drawn, drawn.toString())
    val undrawable =
      data.getValue("undrawable").jsonArray.associate {
        it.jsonObject.getValue("path").jsonPrimitive.content to
          it.jsonObject.getValue("reason").jsonPrimitive.content
      }
    assertContains(
      undrawable.getValue("views[0].sections[1].cards[1]"),
      "custom:mushroom-light-card",
    )
    assertContains(undrawable.getValue("views[0].sections[1].cards[2]"), "no type")
    val images = rendered.images()
    assertTrue(images.isNotEmpty())
    images.forEachIndexed { i, png -> check(png, "dashboard-sample-$i") }
    assertTrue(rendered.captions().first().startsWith("Sample data: Home (view 0), section 1"))

    val dark =
      mcp(
        "render_dashboard",
        buildJsonObject {
          put("session", session)
          put("dark", true)
          put("view", 0)
        },
      )
    assertFalse(dark.isError(), dark.toString())
    dark.images().forEachIndexed { i, png -> check(png, "dashboard-sample-dark-$i") }
    assertTrue(
      mcp(
          "render_dashboard",
          buildJsonObject {
            put("session", session)
            put("view", 3)
          },
        )
        .isError(),
    )

    // Only a remote-compose card keeps a chosen design once applied: it draws its inner card, and
    // a design_id on a bare card is ignored, with a note saying how to keep it.
    val choices =
      Json.parseToJsonElement(
          """
          {"views": [{"title": "Home", "type": "sections", "sections": [{"type": "grid", "cards": [
            {"type": "tile", "entity": "light.porch", "design_id": "pack:nothing-here"},
            {"type": "custom:remote-compose-card", "design_id": "pack:nothing-here",
             "card": {"type": "tile", "entity": "light.porch"}},
            {"type": "custom:remote-compose-card"}
          ]}]}]}
          """,
        )
        .jsonObject
    val chosen =
      mcp(
          "render_dashboard",
          buildJsonObject {
            put("session", session)
            put("dashboard", choices)
          },
        )
        .data()
    val notes =
      chosen.getValue("cards").jsonArray.associate {
        it.jsonObject.getValue("path").jsonPrimitive.content to
          it.jsonObject.getValue("notes").toString()
      }
    assertContains(notes.getValue("views[0].sections[0].cards[0]"), "ignored on a bare card")
    assertContains(notes.getValue("views[0].sections[0].cards[1]"), "pack:nothing-here")
    assertContains(
      chosen
        .getValue("undrawable")
        .jsonArray
        .first { it.jsonObject.getValue("path").jsonPrimitive.content.endsWith("cards[2]") }
        .toString(),
      "needs its card",
    )
    assertContains(
      read.getValue("designIdNote").jsonPrimitive.content,
      "custom:remote-compose-card",
    )

    // A remote-compose card draws in its own card_style, as the app does.
    fun styledDashboard(style: String?): JsonObject {
      val styleField = style?.let { ", \"card_style\": \"$it\"" } ?: ""
      return Json.parseToJsonElement(
          """
          {"views": [{"title": "Home", "type": "sections", "sections": [{"type": "grid", "cards": [
            {"type": "custom:remote-compose-card"$styleField,
             "card": {"type": "tile", "entity": "light.porch"}}
          ]}]}]}
          """,
        )
        .jsonObject
    }
    val classic = rendered(session, styledDashboard(null))
    assertFalse(
      classic.contentEquals(rendered(session, styledDashboard("lcd"))),
      "lcd draws its own",
    )
    assertTrue(
      classic.contentEquals(rendered(session, styledDashboard("classic"))),
      "classic default",
    )
  }

  private suspend fun ApplicationTestBuilder.rendered(
    session: String,
    dashboard: JsonObject,
  ): ByteArray {
    val result =
      mcp(
        "render_dashboard",
        buildJsonObject {
          put("session", session)
          put("dashboard", dashboard)
        },
      )
    assertFalse(result.isError(), result.toString())
    return result.images().single()
  }

  @Test
  fun checkAndSubmitRefuseTheSamePictureBindings() {
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    fun document(digest: String, mediaType: String = "image/png") = buildJsonObject {
      putJsonObject("assets") {
        putJsonObject("door") {
          put("mediaType", mediaType)
          put("contentDigest", digest)
        }
      }
    }
    val good = "sha256:${ee.schimke.ha.design.Sha256.hex(png)}"
    assertEquals(
      emptyList(),
      AgentDesignSessions.bindingProblems(document(good), mapOf("door" to png)),
    )
    assertContains(
      AgentDesignSessions.bindingProblems(document(good), emptyMap()).single(),
      "isn't attached",
    )
    // A stale digest, as after replacing the picture under the same key.
    assertContains(
      AgentDesignSessions.bindingProblems(document("sha256:00"), mapOf("door" to png)).single(),
      "doesn't match its binding",
    )
    assertContains(
      AgentDesignSessions.bindingProblems(document(good, "image/jpeg"), mapOf("door" to png))
        .single(),
      "doesn't match its binding",
    )
  }

  @Test
  fun sampleValuesAreDeterministicAndTyped() {
    val variables =
      Json.parseToJsonElement(
          """
          {"card.title": {"valueType": "string", "initialValue": "Mine"},
           "light.s0.state": {"valueType": "string", "initialValue": "Off"},
           "light.s0.name": {"valueType": "string", "initialValue": "Hall"},
           "light.s0.is_on": {"valueType": "bool", "initialValue": false},
           "sensor.s1.state": {"valueType": "string", "initialValue": "19 °C"},
           "sensor.s1.numeric_state": {"valueType": "float", "initialValue": 19},
           "sensor.s1.state_int": {"valueType": "int", "initialValue": 19},
           "sensor.s1.attributes.friendly_name": {"valueType": "string", "initialValue": "x"},
           "HA:sun.sun.elevation": {"valueType": "float", "initialValue": 0},
           "unknown": {"valueType": "float", "initialValue": 0}}
          """,
        )
        .jsonObject
    val values = AgentSamples.cardValues(variables)
    assertEquals(values, AgentSamples.cardValues(variables), "the same every time")
    assertEquals("Living room", values.getValue("card.title").content)
    assertEquals("On", values.getValue("light.s0.state").content)
    assertEquals("Kitchen light", values.getValue("light.s0.name").content)
    assertEquals(true, values.getValue("light.s0.is_on").boolean)
    assertEquals("21.5 °C", values.getValue("sensor.s1.state").content)
    assertEquals(21.5, values.getValue("sensor.s1.numeric_state").double)
    assertFalse(values.getValue("sensor.s1.state_int").isString)
    assertEquals("Kitchen light", values.getValue("sensor.s1.attributes.friendly_name").content)
    assertEquals(21.5, values.getValue("unknown").double)
    assertFalse(values.getValue("HA:sun.sun.elevation").isString)

    // The library's examples win over made-up values.
    val withExamples =
      AgentSamples.cardValues(variables, mapOf("light.s0.name" to JsonPrimitive("Pantry")))
    assertEquals("Pantry", withExamples.getValue("light.s0.name").content)

    val states = AgentSamples.snapshot(listOf("climate.hallway", "sensor.bathroom_humidity")).states
    assertEquals("heat", states.getValue("climate.hallway").state)
    assertEquals(
      "%",
      states
        .getValue("sensor.bathroom_humidity")
        .attributes["unit_of_measurement"]
        ?.jsonPrimitive
        ?.content,
    )
    assertTrue("sun.sun" in states, "the sun every home shares")
    assertEquals(
      setOf("light.a", "sensor.b", "switch.c"),
      AgentSamples.entityIds(
        Json.parseToJsonElement(
          """{"cards": [{"entity": "light.a"}, {"entities": ["sensor.b", {"entity": "switch.c"}]},
             {"name": "not.an_entity_key"}]}""",
        ),
      ),
    )
  }

  @Test
  fun aSessionKeepsItsCardTypeAndPreviewedKeyAcrossARestart() {
    val sessions = AgentDesignSessions()
    val access = sessions.create("Bigger", buildJsonObject { put("id", "card-x") }, "tile")
    assertTrue(sessions.rememberKey(access.owner, hallKey))
    val restored = AgentDesignSessions()
    restored.restore(sessions.snapshot())
    val context = assertNotNull(restored.context(access.agent))
    assertEquals("tile", context.cardType)
    assertEquals(hallKey, context.key)
    // A file saved before the fields existed restores with neither.
    val old =
      Json.decodeFromString(
        AgentDesignSessions.Saved.serializer(),
        """{"owner":"o","agent":"a","instruction":"i","original":{},"createdAt":0,
           "expiresAt":${Long.MAX_VALUE},"document":{},"revision":0}""",
      )
    assertNull(old.key)
    assertNull(old.cardType)
    assertEquals(
      "baked:Material3:dark",
      AgentGuide.withMode(hallKey.copy(theme = "baked:Material3:light"), true).theme,
    )
  }
}
