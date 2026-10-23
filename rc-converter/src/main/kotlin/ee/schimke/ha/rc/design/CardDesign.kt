@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.design

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.FramedCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The card being drawn while a card design draws it: the card's own config and snapshot when a
 * converter draws its card from its design ([CardDesign.RenderCard]), or a design key's card and
 * placeholder snapshot while a template is captured. Components that draw a card's parts (a tile's
 * icon chip, its name and state) read it. Null in UI Builder's canvas and the catalog's stickers,
 * where a component draws a placeholder card of its own.
 */
val LocalCardInput = staticCompositionLocalOf<DesignTemplateInput?> { null }

/**
 * How a component draws one node of a card design: [node]'s properties and slots, with [modifier]
 * (the one its parent passed, then the node's own modifiers) to draw its root with.
 */
typealias CardDesignComponent = @Composable (node: CardDesignNode, modifier: RemoteModifier) -> Unit

/**
 * A UI Builder card design (`.uid`, `compose-ui-builder-document/v1`) on the `ha-cards` catalog,
 * drawn in this process by calling a component for each node (server-owned-card-designs.md). This
 * is how a card's layout lives in its design rather than in its converter: the converter draws the
 * card by drawing its checked-in design ([CardDesigns]), and the library and the scheduled import
 * capture the same design as templates.
 *
 * [components] decides which component ids it draws. A node it doesn't know, a property that isn't
 * a literal or a modifier it doesn't draw fails [validate] with the node's id, rather than drawing
 * something the design didn't say.
 */
class CardDesign
private constructor(
  val id: String,
  private val document: JsonObject,
  private val components: Map<String, CardDesignComponent>,
) {
  private val nodes: Map<String, JsonObject> =
    document["nodes"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject }

  private val roots: List<String> =
    document["roots"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

  /** The catalog the design is pinned to; only `ha-cards` designs are drawn. */
  val catalog: String? =
    document["catalogPin"]?.jsonObject?.get("systemId")?.jsonPrimitive?.contentOrNull

  /** Every reason this design can't be drawn here, by node; empty when it can. */
  fun validate(): List<String> = buildList {
    if (catalog != CATALOG) add("catalog `$catalog` is not `$CATALOG`")
    if (roots.size != 1) add("a card design has one root, not ${roots.size}")
    nodes.forEach { (id, node) ->
      val component = node["componentId"]?.jsonPrimitive?.contentOrNull
      if (component !in components) add("$id: `$component` is not a component this host draws")
      (node["modifiers"] as? JsonArray).orEmpty().forEach { modifier ->
        val type = (modifier as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
        if (type !in MODIFIERS) add("$id: modifier `$type` is not drawn here")
      }
      node["properties"]?.jsonObject?.forEach { (name, value) ->
        val type = value.jsonObject["type"]?.jsonPrimitive?.contentOrNull
        if (type !in LITERAL_TYPES) add("$id.$name: a `$type` property is not a literal")
      }
      childrenOf(id).filter { it !in nodes }.forEach { add("$id: missing child $it") }
    }
  }

  /** The design, drawn with [modifier] on its root. */
  @Composable
  @RemoteComposable
  fun Render(modifier: RemoteModifier = RemoteModifier) {
    val problems = validate()
    require(problems.isEmpty()) { "card design $id can't be drawn: ${problems.joinToString()}" }
    Node(roots.single(), modifier)
  }

  /**
   * [card] on [snapshot] drawn by this design, as a card: inside the unavailable frame of the one
   * entity it is about ([FramedCard]), as `RenderChild` frames every card.
   */
  @Composable
  @RemoteComposable
  fun RenderCard(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier = RemoteModifier) {
    CompositionLocalProvider(LocalCardInput provides DesignTemplateInput(card, snapshot)) {
      FramedCard(card, snapshot, modifier) { Render(it) }
    }
  }

  /** This design drawn with [more] components as well (a host's own wrappers). */
  fun with(more: Map<String, CardDesignComponent>): CardDesign =
    CardDesign(id, document, components + more)

  internal fun componentOf(id: String): String? =
    nodes[id]?.get("componentId")?.jsonPrimitive?.contentOrNull

  internal fun childrenOf(id: String, slot: String? = null): List<String> {
    val slots = nodes[id]?.get("slots")?.jsonObject.orEmpty()
    val selected = if (slot == null) slots.values else listOfNotNull(slots[slot])
    return selected.flatMap { (it as? JsonArray).orEmpty().map { child -> child.jsonPrimitive.content } }
  }

  @Composable
  internal fun Node(id: String, modifier: RemoteModifier) {
    val node = nodes.getValue(id)
    var applied = modifier
    (node["modifiers"] as? JsonArray).orEmpty().forEach {
      applied =
        when ((it as JsonObject)["type"]?.jsonPrimitive?.contentOrNull) {
          "fillMaxWidth" -> applied.fillMaxWidth()
          "fillMaxHeight" -> applied.fillMaxHeight()
          "fillMaxSize" -> applied.fillMaxSize()
          else -> applied
        }
    }
    val component = components.getValue(componentOf(id)!!)
    component(CardDesignNode(this, id, node["properties"]?.jsonObject.orEmpty()), applied)
  }

  companion object {
    const val CATALOG = "ha-cards"

    private val MODIFIERS = setOf("fillMaxWidth", "fillMaxHeight", "fillMaxSize")

    private val LITERAL_TYPES = setOf("string", "bool", "int", "float", "color")

    private val json = Json { ignoreUnknownKeys = true }

    /** The design in [text], a `.uid` document, drawn with [components]. */
    fun parse(text: String, components: Map<String, CardDesignComponent>): CardDesign {
      val document = json.parseToJsonElement(text).jsonObject
      val id = requireNotNull(document["id"]?.jsonPrimitive?.contentOrNull) { "a .uid has an id" }
      return CardDesign(id, document, components)
    }
  }
}

/** One node of a [CardDesign], as a component draws it: its literal properties and its slots. */
class CardDesignNode
internal constructor(
  private val design: CardDesign,
  val id: String,
  private val properties: Map<String, JsonElement>,
) {
  /** Draws each node in [slot] with [modifier], then each node's own modifiers. */
  @Composable
  fun Slot(slot: String, modifier: RemoteModifier = RemoteModifier) {
    design.childrenOf(id, slot).forEach { design.Node(it, modifier) }
  }

  /** Draws every node in every slot, in order, with [modifier]: a container with one body. */
  @Composable
  fun Children(modifier: RemoteModifier = RemoteModifier) {
    design.childrenOf(id).forEach { design.Node(it, modifier) }
  }

  /** The nodes in [slot]: so a component can pass its own modifier to a single child. */
  fun children(slot: String): List<String> = design.childrenOf(id, slot)

  /** Draws one child [childId] (one of [children]) with [modifier]. */
  @Composable fun Child(childId: String, modifier: RemoteModifier) = design.Node(childId, modifier)

  /** The component id of child [childId]. */
  fun componentOf(childId: String): String? = design.componentOf(childId)

  private fun literal(name: String): JsonPrimitive? =
    properties[name]?.jsonObject?.get("value") as? JsonPrimitive

  fun string(name: String, default: String = ""): String = literal(name)?.contentOrNull ?: default

  fun bool(name: String, default: Boolean = false): Boolean = literal(name)?.booleanOrNull ?: default

  fun int(name: String, default: Int = 0): Int = literal(name)?.intOrNull ?: default

  fun float(name: String, default: Float = 0f): Float = literal(name)?.floatOrNull ?: default

  /** An `#AARRGGBB` (or `#RRGGBB`) literal as ARGB, or null when unset. */
  fun argb(name: String): Int? {
    val hex = literal(name)?.contentOrNull?.removePrefix("#") ?: return null
    return hex.toLong(16).let { if (hex.length <= 6) it or 0xFF000000 else it }.toInt()
  }
}
