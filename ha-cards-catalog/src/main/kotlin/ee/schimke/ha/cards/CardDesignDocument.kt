@file:Suppress("RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
 * A UI Builder card design (`.uid`, `compose-ui-builder-document/v1`) on the `ha-cards` catalog,
 * drawn in this process by calling the catalog's own components with each node's properties: the
 * in-repository half of the `.uid` → `.rc` transformation (server-owned-card-designs.md). It needs
 * no UI Builder, so the library and the scheduled import can capture designs in a Robolectric test.
 *
 * Only `ha-cards/` components are drawn. A node this renderer doesn't know, a state-bound property
 * or a modifier fails [validate] with the node's id, rather than drawing something the design
 * didn't say.
 */
class CardDesignDocument private constructor(val id: String, private val document: JsonObject) {

  private val nodes: Map<String, JsonObject> =
    document["nodes"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject }

  private val roots: List<String> =
    document["roots"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

  /** The catalog the design is pinned to; this renderer draws only `ha-cards`. */
  val catalog: String? =
    document["catalogPin"]?.jsonObject?.get("systemId")?.jsonPrimitive?.contentOrNull

  /** Every reason this design can't be drawn here, by node; empty when it can. */
  fun validate(): List<String> = buildList {
    if (catalog != CATALOG) add("catalog `$catalog` is not `$CATALOG`")
    if (roots.size != 1) add("a card design has one root, not ${roots.size}")
    roots.forEach { root ->
      if (componentOf(root) !in ROOTS) add("$root: `${componentOf(root)}` is not a card root")
    }
    nodes.forEach { (id, node) ->
      val component = node["componentId"]?.jsonPrimitive?.contentOrNull
      if (component !in DRAWN) add("$id: `$component` is not drawn by the in-repo renderer")
      if ((node["modifiers"] as? JsonArray)?.isNotEmpty() == true) add("$id: modifiers")
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

  private fun componentOf(id: String): String? =
    nodes[id]?.get("componentId")?.jsonPrimitive?.contentOrNull

  private fun childrenOf(id: String): List<String> =
    nodes[id]?.get("slots")?.jsonObject?.values.orEmpty().flatMap { slot ->
      (slot as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    }

  @Composable
  @RemoteComposable
  private fun Children(id: String) {
    childrenOf(id).forEach { Node(it, RemoteModifier) }
  }

  @Composable
  @RemoteComposable
  private fun Node(id: String, modifier: RemoteModifier) {
    val p = Properties(nodes.getValue(id)["properties"]?.jsonObject.orEmpty())
    when (componentOf(id)) {
      // A dashboard card holding one whole card is that card, with its own chrome: a tile in a
      // dashboard card is one card, not a card in a card. Anything else sits on the card surface.
      "ha-cards/dashboard-card" -> {
        val children = childrenOf(id)
        val only = children.singleOrNull()
        if (only != null && componentOf(only) in WHOLE_CARDS) Node(only, modifier)
        else HaCardSurface(modifier) { Children(id) }
      }
      "ha-cards/dashboard-section" -> HaDashboardSection(modifier) { Children(id) }
      "ha-cards/card-surface" -> HaCardSurface(modifier) { Children(id) }
      "ha-cards/card-template" -> HaCardTemplate(modifier) { Children(id) }
      "ha-cards/uber-theme" ->
        HaCardUberTheme(p.string("theme", "classic"), modifier, p.bool("dark")) { Children(id) }
      "ha-cards/entity-tile" ->
        HaCardEntityTile(
          modifier,
          domain = p.string("domain"),
          deviceClass = p.string("deviceClass"),
          iconAttribute = p.string("iconAttribute"),
          iconConfig = p.string("iconConfig"),
          unit = p.string("unit"),
        )
      "ha-cards/tile" ->
        HaCardTile(
          p.string("name"),
          p.string("state"),
          modifier,
          entityId = p.string("entityId"),
          icon = p.string("icon", "light"),
          toggleable = p.bool("toggleable"),
          isOn = p.bool("isOn"),
          activeColor = p.color("activeColor") ?: HaCardColors.Active,
          inactiveColor = p.color("inactiveColor") ?: HaCardColors.Inactive,
        )
      "ha-cards/entity-row" ->
        HaCardEntityRow(
          p.string("name"),
          p.string("state"),
          modifier,
          entityId = p.string("entityId"),
          icon = p.string("icon", "light"),
          toggleable = p.bool("toggleable"),
          isOn = p.bool("isOn"),
          activeColor = p.color("activeColor") ?: HaCardColors.Active,
          inactiveColor = p.color("inactiveColor") ?: HaCardColors.Inactive,
        )
      "ha-cards/gauge" ->
        HaCardGauge(
          p.string("name"),
          p.float("value"),
          modifier,
          min = p.float("min", 0f),
          max = p.float("max", 100f),
          unit = p.string("unit"),
          entityId = p.string("entityId"),
        )
      "ha-cards/heading" -> HaCardHeading(p.string("title"), modifier, p.bool("subtitle"))
      "ha-cards/button" ->
        HaCardButton(
          p.string("name"),
          modifier,
          entityId = p.string("entityId"),
          icon = p.string("icon", "light"),
          showName = p.bool("showName", true),
          toggleable = p.bool("toggleable"),
          isOn = p.bool("isOn"),
          activeColor = p.color("activeColor") ?: HaCardColors.Active,
          inactiveColor = p.color("inactiveColor") ?: HaCardColors.Inactive,
        )
      "ha-cards/toggle" ->
        HaCardToggle(
          p.bool("isOn"),
          modifier,
          entityId = p.string("entityId"),
          activeColor = p.color("activeColor") ?: HaCardColors.Active,
          inactiveColor = p.color("inactiveColor") ?: HaCardColors.Inactive,
        )
      "ha-cards/icon" ->
        HaCardIcon(
          p.string("icon", "light"),
          modifier,
          color = p.color("color") ?: HaCardColors.Active,
          sizeDp = p.int("sizeDp", 24),
        )
      "ha-cards/metric" ->
        HaCardMetric(
          p.string("label"),
          p.string("value"),
          modifier,
          unit = p.string("unit"),
          entityId = p.string("entityId"),
          valueColor = p.color("valueColor"),
        )
      else -> error("$id: `${componentOf(id)}` is not drawn by the in-repo renderer")
    }
  }

  /** A node's literal properties, read with the component's own defaults. */
  private class Properties(private val values: Map<String, JsonElement>) {
    private fun literal(name: String): JsonPrimitive? =
      values[name]?.jsonObject?.get("value") as? JsonPrimitive

    fun string(name: String, default: String = ""): String = literal(name)?.contentOrNull ?: default

    fun bool(name: String, default: Boolean = false): Boolean =
      literal(name)?.booleanOrNull ?: default

    fun int(name: String, default: Int = 0): Int = literal(name)?.intOrNull ?: default

    fun float(name: String, default: Float = 0f): Float = literal(name)?.floatOrNull ?: default

    /** A `#AARRGGBB` (or `#RRGGBB`) literal, or null when unset. */
    fun color(name: String): RemoteColor? {
      val hex = literal(name)?.contentOrNull?.removePrefix("#") ?: return null
      val argb = hex.toLong(16).let { if (hex.length <= 6) it or 0xFF000000 else it }
      return Color(argb.toInt()).rc
    }
  }

  companion object {
    const val CATALOG = "ha-cards"

    private val ROOTS = setOf("ha-cards/dashboard-card", "ha-cards/dashboard-section")

    /** Components that are a whole HA card, drawing their own chrome. */
    private val WHOLE_CARDS =
      setOf("ha-cards/entity-tile", "ha-cards/tile", "ha-cards/gauge", "ha-cards/button")

    private val DRAWN =
      ROOTS +
        WHOLE_CARDS +
        setOf(
          "ha-cards/card-surface",
          "ha-cards/card-template",
          "ha-cards/uber-theme",
          "ha-cards/entity-row",
          "ha-cards/heading",
          "ha-cards/toggle",
          "ha-cards/icon",
          "ha-cards/metric",
        )

    private val LITERAL_TYPES = setOf("string", "bool", "int", "float", "color")

    private val json = Json { ignoreUnknownKeys = true }

    /** The design in [text], a `.uid` document. */
    fun parse(text: String): CardDesignDocument {
      val document = json.parseToJsonElement(text).jsonObject
      val id = requireNotNull(document["id"]?.jsonPrimitive?.contentOrNull) { "a .uid has an id" }
      return CardDesignDocument(id, document)
    }
  }
}
