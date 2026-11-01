package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
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

  /** Convert a fitted image scene into content for the fallback compiler, without host chrome. */
  internal fun compilableScene(document: JsonObject): JsonObject? {
    val rootId =
      (document["roots"] as? JsonArray)?.singleOrNull()?.jsonPrimitive?.content ?: return null
    val nodes = document["nodes"] as? JsonObject ?: return null
    val root = nodes[rootId] as? JsonObject ?: return null
    if (root["componentId"]?.jsonPrimitive?.content !in CONTAINERS.values.map { it.component })
      return null
    val children = (root["slots"] as? JsonObject)?.get("content") as? JsonArray ?: return null
    val replacement =
      UiBuilderSeed.node(
        rootId,
        "layout/box",
        modifiers = listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize")))),
        slots = mapOf("children" to children.map { it.jsonPrimitive.content }),
      )
    return JsonObject(document + ("nodes" to JsonObject(nodes + (rootId to replacement))))
  }

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

    // A fixed image scene's root is its coordinate system, not disposable card chrome. Preserve
    // it and fit the whole scene into the host's padded content area with one uniform scale.
    if (
      root["componentId"]?.jsonPrimitive?.content == "layout/box" &&
        nodes.values.any { it["componentId"]?.jsonPrimitive?.content == "asset/image" } &&
        (root["modifiers"] as? JsonArray).orEmpty().any {
          (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "size"
        }
    ) {
      // Refuse an unsupported scene instead of dropping its coordinate root and cropping it.
      return fixedScene(card, nodes, rootId, target, container)
    }

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

  private fun fixedScene(
    card: JsonObject,
    nodes: Map<String, JsonObject>,
    rootId: String,
    target: Container,
    container: String,
  ): JsonObject? {
    if (
      nodes.values.any {
        it["componentId"]?.jsonPrimitive?.content !in setOf("layout/box", "asset/image")
      }
    )
      return null
    if (nodes.values.none { it["componentId"]?.jsonPrimitive?.content == "asset/image" })
      return null
    val root = nodes.getValue(rootId)
    if (root["componentId"]?.jsonPrimitive?.content != "layout/box") return null
    val size =
      (root["modifiers"] as? JsonArray)
        .orEmpty()
        .mapNotNull { it as? JsonObject }
        .singleOrNull { it["type"]?.jsonPrimitive?.content == "size" } ?: return null
    val width = (size["widthDp"] as? JsonPrimitive)?.floatOrNull ?: return null
    val height = (size["heightDp"] as? JsonPrimitive)?.floatOrNull ?: return null
    if (width <= 0 || height <= 0) return null
    val scale = minOf((target.widthDp - 16f) / width, (target.heightDp - 16f) / height)
    val dimensions =
      mapOf(
        "size" to setOf("widthDp", "heightDp"),
        "width" to setOf("widthDp"),
        "height" to setOf("heightDp"),
        "offset" to setOf("xDp", "yDp"),
        "padding" to setOf("startDp", "topDp", "endDp", "bottomDp"),
      )
    val scaled = LinkedHashMap<String, JsonElement>()
    for ((id, node) in nodes) {
      val modifiers =
        (node["modifiers"] as? JsonArray).orEmpty().map { value ->
          val modifier = value as? JsonObject ?: return null
          val type = modifier["type"]?.jsonPrimitive?.content ?: return null
          if (
            type !in dimensions &&
              type !in
                setOf(
                  "fillMaxSize",
                  "fillMaxWidth",
                  "fillMaxHeight",
                  "background",
                  "alpha",
                  "scale",
                  "rotate",
                  "remoteCall",
                )
          )
            return null
          if (
            type == "remoteCall" &&
              (modifier["name"]?.jsonPrimitive?.content != "graphicsLayer" ||
                (modifier["args"] as? JsonObject).orEmpty().keys.any {
                  it !in
                    setOf(
                      "rotationX",
                      "rotationY",
                      "rotationZ",
                      "transformOriginX",
                      "transformOriginY",
                      "cameraDistance",
                      "alpha",
                      "scaleX",
                      "scaleY",
                    )
                })
          )
            return null
          val fields = dimensions[type].orEmpty()
          JsonObject(
            modifier.mapValues { (key, value) ->
              if (
                type == "remoteCall" &&
                  key == "args" &&
                  value is JsonObject &&
                  "cameraDistance" in value
              ) {
                val camera = value.getValue("cameraDistance") as? JsonObject ?: return null
                val distance = (camera["value"] as? JsonPrimitive)?.floatOrNull ?: return null
                if (camera["type"]?.jsonPrimitive?.content !in setOf("float", "int")) return null
                JsonObject(
                  value +
                    ("cameraDistance" to
                      JsonObject(
                        camera +
                          ("type" to JsonPrimitive("float")) +
                          ("value" to JsonPrimitive(distance * scale))
                      ))
                )
              } else if (key !in fields) value
              else JsonPrimitive(((value as? JsonPrimitive)?.floatOrNull ?: return null) * scale)
            }
          )
        }
      scaled[id] = JsonObject(node + ("modifiers" to JsonArray(modifiers)))
    }
    fun fresh(base: String): String = generateSequence(base) { "$it-" }.first { it !in scaled }
    val frame = fresh("__wear_frame")
    scaled[frame] =
      UiBuilderSeed.node(
        frame,
        "layout/box",
        properties = mapOf("contentAlignment" to UiBuilderSeed.string("center")),
        modifiers = listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize")))),
        slots = mapOf("children" to listOf(rootId)),
      )
    val widget = fresh("__wear_widget")
    scaled[widget] =
      UiBuilderSeed.node(
        widget,
        target.component,
        properties = mapOf("background" to UiBuilderSeed.string(HaCardsSeed.WEAR_BACKGROUND)),
        slots = mapOf("content" to listOf(frame)),
      )
    val environment = (card["environment"] as? JsonObject).orEmpty()
    return JsonObject(
      card - "template" +
        mapOf(
          "id" to JsonPrimitive("${card["id"]?.jsonPrimitive?.content.orEmpty()}-wear-$container"),
          "roots" to JsonArray(listOf(JsonPrimitive(widget))),
          "nodes" to JsonObject(scaled),
          "environment" to
            JsonObject(
              environment +
                mapOf(
                  "widthDp" to JsonPrimitive(target.widthDp),
                  "heightDp" to JsonPrimitive(target.heightDp),
                  "theme" to JsonPrimitive("dark"),
                )
            ),
        )
    )
  }
}
