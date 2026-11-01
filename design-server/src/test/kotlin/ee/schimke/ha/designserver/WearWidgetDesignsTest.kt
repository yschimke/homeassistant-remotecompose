package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignKey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A card's UI Builder design becomes its Wear widgets: the card without its surface. */
class WearWidgetDesignsTest {

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
              )
            ))
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
    """
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
                          """[{"type":"offset","xDp":{"type":"state","variable":"cover.s0.is_on"},"yDp":0}]"""
                        ))
                  ))
            ))
      )
    assertNull(
      WearWidgetDesigns.document(unsafe, DesignKey.WEAR_SMALL),
      "unsupported geometry must not be silently cropped",
    )
  }
}
