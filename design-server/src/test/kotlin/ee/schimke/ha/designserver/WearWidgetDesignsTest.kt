package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.designs.LibraryCaptureProfile
import ee.schimke.ha.designs.UiBuilderDesignCompiler
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CommonCaptureHost
import ee.schimke.ha.rc.components.ThemeStyle
import java.util.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A card's UI Builder design becomes its Wear widgets: the card without its surface. */
class WearWidgetDesignsTest {

  @Test
  fun fittedImageSceneCompilesForTheWearPlayerAndKeepsItsLiveBinding() {
    val source =
      Json.parseToJsonElement(
          """
      {"id":"garage","catalogPin":{"systemId":"ha-cards"},
       "roots":["stage"],"environment":{"widthDp":360,"heightDp":300},
       "stateVariables":{"cover.s0.is_on":{"type":"value","valueType":"bool","initialValue":false},"HA:sun.sun.elevation":{"type":"value","valueType":"float","initialValue":45}},
       "nodes":{
         "stage":{"id":"stage","componentId":"layout/box","properties":{},"modifiers":[{"type":"size","widthDp":360,"heightDp":300}],"slots":{"children":["door","night"]}},
         "door":{"id":"door","componentId":"asset/image","properties":{"assetKey":{"type":"string","value":"door"},"contentScale":{"type":"string","value":"fillBounds"}},"modifiers":[{"type":"size","widthDp":200,"heightDp":180},{"type":"remoteCall","name":"graphicsLayer","args":{"rotationX":{"type":"expr","op":"select","args":[{"type":"state","variable":"cover.s0.is_on"},{"type":"float","value":85},{"type":"float","value":0}]}}}],"slots":{}},
         "night":{"id":"night","componentId":"layout/box","properties":{},"modifiers":[{"type":"fillMaxSize"},{"type":"alpha","alpha":{"type":"expr","op":"mul","args":[{"type":"float","value":0.55},{"type":"expr","op":"sub","args":[{"type":"float","value":1},{"type":"expr","op":"smoothstep","args":[{"type":"float","value":-6},{"type":"float","value":6},{"type":"state","variable":"HA:sun.sun.elevation"}]}]}]}}],"slots":{"children":[]}}
       }}
    """,
        )
        .jsonObject
    val png =
      Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=",
        )
    // Exercise the actual cloud seed and agent handoff, not just a standalone scene. An agent
    // retains the seed root and binding declarations while replacing its content with the scene.
    val cardConfig =
      Json.parseToJsonElement(
          """{"type":"custom:enhanced-shutter-card","grid_options":{"columns":"full"},"entities":[{"entity":"cover.garage","name":"Garage Door","top_offset_pct":13}]}""",
        )
        .jsonObject
    val states =
      mapOf(
        "cover.garage" to
          EntityState(
            "cover.garage",
            "closed",
            JsonObject(mapOf("device_class" to JsonPrimitive("garage"))),
          ),
      )
    val key =
      assertNotNull(
        CardDesignTemplates.selectedDesignKeyFor(
          CardConfig("custom:enhanced-shutter-card", cardConfig),
          HaSnapshot(states = states),
          ThemeStyle.Material3,
          dark = false,
        ),
      )
    assertEquals("device_class=garage;domain=cover;state_icon=garage", key.shape)
    val seed =
      UiBuilderSeed.document(
        CardSchema.fromCard(cardConfig, states),
        "card-test",
        "Garage",
        HaCardsSeed.card("app-card"),
      )
    val sessions = AgentDesignSessions()
    val access = sessions.create("Redesign the garage", seed)
    assertTrue(sessions.putAsset(access.agent, "door", png))
    val asset = assertNotNull(sessions.asset(access.owner, "door"))
    val binding = buildJsonObject {
      put("mediaType", asset.mediaType)
      put("contentDigest", "sha256:${asset.sha256}")
      put(
        "source",
        buildJsonObject {
          put("type", "uploaded")
          put("storageKey", "sha256:${asset.sha256}")
        },
      )
    }
    val root = seed.getValue("nodes").jsonObject.getValue("root").jsonObject
    val slot = root.getValue("slots").jsonObject.keys.single()
    val wrapper =
      JsonObject(
        root + ("slots" to JsonObject(mapOf(slot to JsonArray(listOf(JsonPrimitive("stage")))))),
      )
    val candidate =
      JsonObject(
        seed +
          ("nodes" to JsonObject(source.getValue("nodes").jsonObject + ("root" to wrapper))) +
          ("assets" to JsonObject(mapOf("door" to binding))),
      )
    assertTrue(sessions.submit(access.agent, 0, candidate))
    val submitted = assertNotNull(sessions.read(access.owner)).document
    val baseline =
      CardDesignTemplates.capture(CommonCaptureHost(LibraryCaptureProfile.OPERATIONS), key)
    val baselineDocument = RcDocumentCodec.decode(baseline.bytes, RcWireLimits())
    val base =
      DecodedTemplate(
        listOf(Binding("cover.s0.state", "text")),
        0,
        emptyMap(),
        baselineDocument.validateFor(RcTargetProfiles.ANDROIDX_EXPERIMENTAL).map { it.message },
        null,
      )
    val mobile = UiBuilderDesignCompiler().compile(submitted, mapOf("door" to png), key)
    assertNull(userTemplateProblem(mobile, base, likeBase = true))
    for (container in DesignKey.WEAR_CONTAINERS) {
      val widget = assertNotNull(WearWidgetDesigns.document(submitted, container))
      val scene = assertNotNull(WearWidgetDesigns.compilableScene(widget))
      val bytes =
        assertNotNull(compileWearScene(UiBuilderDesignCompiler(), scene, mapOf("door" to png), key))
      val document = RcDocumentCodec.decode(bytes, RcWireLimits())
      assertTrue(document.validateFor(RcTargetProfiles.WEAR_WIDGETS).isEmpty())
      assertTrue(document.namedVariables.any { it.name.endsWith("cover.s0.is_on") })
      assertTrue(document.namedVariables.any { it.name == "HA:sun.sun.elevation" })
      assertNull(
        userTemplateProblem(
          bytes,
          DecodedTemplate(
            listOf(Binding("cover.s0.state", "text")),
            0,
            emptyMap(),
            emptyList(),
            null,
          ),
          RcTargetProfiles.WEAR_WIDGETS,
        ),
      )
      assertNotNull(
        userTemplateProblem(
          bytes,
          DecodedTemplate(
            listOf(Binding("cover.s1.state", "text")),
            0,
            emptyMap(),
            emptyList(),
            null,
          ),
          RcTargetProfiles.WEAR_WIDGETS,
        ),
      )
      assertTrue(
        document.namedVariables.any { it.name.endsWith("wear.container.horizontal_padding") },
      )
      assertTrue(
        document.namedVariables.any { it.name.endsWith("wear.container.vertical_padding") },
      )
    }
  }

  /** The editable source of a library card: section root → template → surface → body. */
  private val card: JsonObject =
    UiBuilderSeed.envelope(
      HaCardsSeed.section(template = "starter"),
      designId = "card-tile",
      title = "Tile",
      heightDp = 120,
      stateVariables = emptyList(),
      nodes =
        mapOf(
          "root" to
            UiBuilderSeed.node(
              "root",
              HaCardsSeed.DASHBOARD_SECTION,
              slots = mapOf("content" to listOf("template")),
            ),
          "template" to
            UiBuilderSeed.node(
              "template",
              HaCardsSeed.CARD_TEMPLATE,
              slots = mapOf("children" to listOf("surface")),
            ),
          "surface" to
            UiBuilderSeed.node(
              "surface",
              HaCardsSeed.CARD_SURFACE,
              slots = mapOf("content" to listOf("tile", "row")),
            ),
          "tile" to UiBuilderSeed.node("tile", HaCardsSeed.TILE),
          "row" to UiBuilderSeed.node("row", "layout/row"),
        ),
    )

  private fun JsonObject.node(id: String) = getValue("nodes").jsonObject.getValue(id).jsonObject

  private fun JsonObject.children(id: String, slot: String) =
    node(id).getValue("slots").jsonObject.getValue(slot).jsonArray.map { it.jsonPrimitive.content }

  @Test
  fun aCardIsRootedOnEachWearContainerWithoutItsSurface() {
    for ((container, component, height) in
      listOf(
        Triple(DesignKey.WEAR_SMALL, HaCardsSeed.WEAR_SMALL, 76),
        Triple(DesignKey.WEAR_LARGE, HaCardsSeed.WEAR_LARGE, 124),
      )) {
      val wear = WearWidgetDesigns.document(card, container)!!
      assertEquals(component, wear.node("root").getValue("componentId").jsonPrimitive.content)
      assertEquals(listOf("template"), wear.children("root", "content"))
      // The surface's place goes to what it held: the container draws the shape and background.
      assertEquals(listOf("tile", "row"), wear.children("template", "children"))
      assertFalse("surface" in wear.getValue("nodes").jsonObject)
      val environment = wear.getValue("environment").jsonObject
      assertEquals(216, environment.getValue("widthDp").jsonPrimitive.content.toInt())
      assertEquals(height, environment.getValue("heightDp").jsonPrimitive.content.toInt())
      assertEquals("dark", environment.getValue("theme").jsonPrimitive.content)
      assertEquals("card-tile-wear-$container", wear.getValue("id").jsonPrimitive.content)
      // A design, not a starting template.
      assertNull(wear["template"]?.jsonPrimitive?.contentOrNull)
      // The card's catalog, state and body are kept as drawn.
      assertEquals(card["catalogPin"], wear["catalogPin"])
      assertEquals(card.node("tile"), wear.node("tile"))
    }
  }

  @Test
  fun aCardRootIsDroppedAndNestedSurfacesUnwrap() {
    val nested =
      JsonObject(
        card +
          ("nodes" to
            JsonObject(
              mapOf(
                "root" to
                  UiBuilderSeed.node(
                    "root",
                    HaCardsSeed.DASHBOARD_CARD,
                    slots = mapOf("content" to listOf("outer")),
                  ),
                "outer" to
                  UiBuilderSeed.node(
                    "outer",
                    HaCardsSeed.CARD_SURFACE,
                    slots = mapOf("content" to listOf("inner")),
                  ),
                "inner" to
                  UiBuilderSeed.node(
                    "inner",
                    HaCardsSeed.CARD_SURFACE,
                    slots = mapOf("content" to listOf("tile")),
                  ),
                "tile" to UiBuilderSeed.node("tile", HaCardsSeed.TILE),
              ),
            )),
      )
    val wear = WearWidgetDesigns.document(nested, DesignKey.WEAR_SMALL)!!
    assertEquals(listOf("tile"), wear.children("root", "content"))
    assertEquals(setOf("root", "tile"), wear.getValue("nodes").jsonObject.keys)
  }

  @Test
  fun anUnknownContainerOrADesignWithoutOneRootHasNoWearWidget() {
    assertNull(WearWidgetDesigns.document(card, "medium"))
    assertNull(WearWidgetDesigns.document(JsonObject(card - "roots"), DesignKey.WEAR_SMALL))
  }

  @Test
  fun fixedIllustrationFitsEachWidgetWithoutStretchingOrLosingTheDoor() {
    val scene =
      Json.parseToJsonElement(
          """
      {"id":"garage", "roots":["stage"], "environment":{"widthDp":360,"heightDp":301},
       "stateVariables":{"cover.s0.is_on":{"type":"value","valueType":"bool","initialValue":false}},
       "nodes":{
         "stage":{"id":"stage","componentId":"layout/box","modifiers":[{"type":"size","widthDp":360,"heightDp":300.5}],"slots":{"children":["wall","door"]}},
         "wall":{"id":"wall","componentId":"asset/image","modifiers":[{"type":"fillMaxSize"},{"type":"remoteCall","name":"graphicsLayer","args":{"rotationX":{"type":"state","variable":"cover.s0.is_on"},"cameraDistance":{"type":"float","value":32}}}],"slots":{}},
         "door":{"id":"door","componentId":"layout/box","modifiers":[{"type":"offset","xDp":36.8,"yDp":80.6},{"type":"size","widthDp":284.5,"heightDp":191.7}],"slots":{"children":[]}}
       }}
    """,
        )
        .jsonObject
    for ((container, contentHeight) in
      listOf(DesignKey.WEAR_SMALL to 60f, DesignKey.WEAR_LARGE to 108f)) {
      val wear = WearWidgetDesigns.document(scene, container)!!
      val nodes = wear.getValue("nodes").jsonObject
      val widget = wear.getValue("roots").jsonArray.single().jsonPrimitive.content
      val frame = wear.children(widget, "content").single()
      assertEquals(listOf("stage"), wear.children(frame, "children"))
      val stageSize = wear.node("stage").getValue("modifiers").jsonArray.single().jsonObject
      val stageWidth = stageSize.getValue("widthDp").jsonPrimitive.float
      val stageHeight = stageSize.getValue("heightDp").jsonPrimitive.float
      val scale = stageWidth / 360f
      assertTrue(abs(scale - stageHeight / 300.5f) < 0.0001f)
      assertTrue(stageWidth <= 200f && stageHeight <= contentHeight + 0.001f)
      val door = nodes.getValue("door").jsonObject.getValue("modifiers").jsonArray
      val offset = door[0].jsonObject
      val size = door[1].jsonObject
      val x = offset.getValue("xDp").jsonPrimitive.float
      val y = offset.getValue("yDp").jsonPrimitive.float
      val w = size.getValue("widthDp").jsonPrimitive.float
      val h = size.getValue("heightDp").jsonPrimitive.float
      assertTrue(abs(x / 36.8f - scale) < 0.0001f)
      assertTrue(abs(y / 80.6f - scale) < 0.0001f)
      assertTrue(abs(w / 284.5f - scale) < 0.0001f)
      assertTrue(abs(h / 191.7f - scale) < 0.0001f)
      assertTrue(x >= 0 && y >= 0 && x + w <= stageWidth && y + h <= stageHeight)
      // Check all four door corners against the actual rounded host outline, not just its bounds.
      val hosts =
        if (container == DesignKey.WEAR_SMALL)
          listOf(Triple(216f, 76f, 26f), Triple(230f, 76f, 38f))
        else listOf(Triple(216f, 124f, 26f), Triple(230f, 168f, 84f))
      for ((hostWidth, hostHeight, radius) in hosts) {
        val left = (hostWidth - stageWidth) / 2f + x
        val top = (hostHeight - stageHeight) / 2f + y
        for (px in listOf(left, left + w)) for (py in listOf(top, top + h)) {
          val cx = px.coerceIn(radius, hostWidth - radius)
          val cy = py.coerceIn(radius, hostHeight - radius)
          assertTrue(
            (px - cx) * (px - cx) + (py - cy) * (py - cy) <= radius * radius,
            "$container door edge outside rounded host",
          )
        }
      }
      val camera =
        nodes
          .getValue("wall")
          .jsonObject
          .getValue("modifiers")
          .jsonArray[1]
          .jsonObject
          .getValue("args")
          .jsonObject
          .getValue("cameraDistance")
          .jsonObject
          .getValue("value")
          .jsonPrimitive
          .float
      assertTrue(abs(camera / 32f - scale) < 0.0001f)
      assertEquals(scene["stateVariables"], wear["stateVariables"])
    }
    // Card Studio keeps its seeded card root; its single image scene still needs its coordinate
    // system fitted as a whole instead of having the stage discarded as card chrome.
    val wrapped =
      JsonObject(
        scene +
          ("roots" to Json.parseToJsonElement("""["card-root"]""")) +
          ("nodes" to
            JsonObject(
              scene.getValue("nodes").jsonObject +
                ("card-root" to
                  UiBuilderSeed.node(
                    "card-root",
                    HaCardsSeed.DASHBOARD_CARD,
                    slots = mapOf("content" to listOf("stage")),
                  )),
            )),
      )
    for (container in DesignKey.WEAR_CONTAINERS) {
      val wear = assertNotNull(WearWidgetDesigns.document(wrapped, container))
      val widget = wear.getValue("roots").jsonArray.single().jsonPrimitive.content
      val frame = wear.children(widget, "content").single()
      assertEquals(listOf("stage"), wear.children(frame, "children"))
      assertFalse("card-root" in wear.getValue("nodes").jsonObject)
      assertNotNull(WearWidgetDesigns.compilableScene(wear))
    }
    val wrappedNodes = wrapped.getValue("nodes").jsonObject
    val withSibling =
      JsonObject(
        wrapped +
          ("nodes" to
            JsonObject(
              wrappedNodes +
                ("card-root" to
                  UiBuilderSeed.node(
                    "card-root",
                    HaCardsSeed.DASHBOARD_CARD,
                    slots = mapOf("content" to listOf("stage", "other")),
                  )) +
                ("other" to UiBuilderSeed.node("other", "layout/box")),
            )),
      )
    val siblings = assertNotNull(WearWidgetDesigns.document(withSibling, DesignKey.WEAR_LARGE))
    assertEquals(listOf("stage", "other"), siblings.children("card-root", "content"))
    val nodes = scene.getValue("nodes").jsonObject
    val unsafe =
      JsonObject(
        scene +
          ("nodes" to
            JsonObject(
              nodes +
                ("door" to
                  JsonObject(
                    nodes.getValue("door").jsonObject +
                      ("modifiers" to
                        Json.parseToJsonElement(
                          """[{"type":"offset","xDp":{"type":"state","variable":"cover.s0.is_on"},"yDp":0}]""",
                        )),
                  )),
            )),
      )
    assertNull(
      WearWidgetDesigns.document(unsafe, DesignKey.WEAR_SMALL),
      "unsupported geometry must not be silently cropped",
    )
  }
}
