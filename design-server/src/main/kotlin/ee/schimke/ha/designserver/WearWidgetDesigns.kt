package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wear widget designs made from a card design: the card's own UI Builder document, rooted on a
 * Glance Wear widget container instead of the card's root. Glance Wear draws the container's shape
 * and background, so the card's surface goes (its background and border would double up); the rest
 * of the card — its template, theme and body — stays as the person drew it, and adapts to the
 * container's size as it does to a card's.
 *
 * One per container ([DesignKey.WEAR_CONTAINERS]), at its UI Builder environment size
 * ([HaCardsSeed.wearSmall], [HaCardsSeed.wearLarge]).
 */
object WearWidgetDesigns {
  /** The components that draw a card's own surface: left out of a Wear widget. */
  private val SURFACES = setOf(HaCardsSeed.CARD_SURFACE, HaCardsSeed.DASHBOARD_CARD)

  private class Container(val component: String, val widthDp: Int, val heightDp: Int)

  private val CONTAINERS =
    mapOf(
      DesignKey.WEAR_SMALL to Container(HaCardsSeed.WEAR_SMALL, widthDp = 216, heightDp = 76),
      DesignKey.WEAR_LARGE to Container(HaCardsSeed.WEAR_LARGE, widthDp = 216, heightDp = 124),
    )

  /**
   * [card] (a UI Builder document) as a Wear widget for [container]: its root's children under the
   * container root, every card surface replaced by what it holds, and the environment the
   * container's size, dark. Null when [container] isn't one, or [card] has no single root.
   */
  fun document(card: JsonObject, container: String): JsonObject? {
    val target = CONTAINERS[container] ?: return null
    val rootId =
      (card["roots"] as? JsonArray)?.singleOrNull()?.jsonPrimitive?.content ?: return null
    val nodes = (card["nodes"] as? JsonObject)?.mapValues { it.value.jsonObject } ?: return null
    val root = nodes[rootId] ?: return null

    fun children(node: JsonObject): List<String> =
      (node["slots"] as? JsonObject)?.values.orEmpty().flatMap { ids ->
        ids.jsonArray.map { it.jsonPrimitive.content }
      }
    fun isSurface(id: String) = nodes[id]?.get("componentId")?.jsonPrimitive?.content in SURFACES
    // A surface's place goes to what it holds, surfaces inside it unwrapped too.
    fun unwrapped(ids: List<String>): List<String> = ids.flatMap { id ->
      if (isSurface(id)) unwrapped(children(nodes.getValue(id))) else listOf(id)
    }

    val out = LinkedHashMap<String, JsonElement>()
    out[rootId] =
      JsonObject(
        mapOf(
          "id" to JsonPrimitive(rootId),
          "componentId" to JsonPrimitive(target.component),
          "properties" to
            JsonObject(mapOf("background" to UiBuilderSeed.string(HaCardsSeed.WEAR_BACKGROUND))),
          "modifiers" to JsonArray(emptyList()),
          "slots" to
            JsonObject(
              mapOf("content" to JsonArray(unwrapped(children(root)).map(::JsonPrimitive)))
            ),
        )
      )
    for ((id, node) in nodes) {
      if (id == rootId || isSurface(id)) continue
      val slots = node["slots"] as? JsonObject
      out[id] =
        if (slots == null) node
        else
          JsonObject(
            node +
              ("slots" to
                JsonObject(
                  slots.mapValues { (_, ids) ->
                    JsonArray(
                      unwrapped(ids.jsonArray.map { it.jsonPrimitive.content }).map(::JsonPrimitive)
                    )
                  }
                ))
          )
    }
    val environment = (card["environment"] as? JsonObject).orEmpty()
    val id = card["id"]?.jsonPrimitive?.content.orEmpty()
    return JsonObject(
      card - "template" +
        mapOf(
          "id" to JsonPrimitive("$id-wear-$container"),
          "environment" to
            JsonObject(
              environment +
                mapOf(
                  "widthDp" to JsonPrimitive(target.widthDp),
                  "heightDp" to JsonPrimitive(target.heightDp),
                  "theme" to JsonPrimitive("dark"),
                )
            ),
          "nodes" to JsonObject(out),
        )
    )
  }
}
