package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
}
