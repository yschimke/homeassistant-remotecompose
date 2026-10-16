package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.designserver.UiBuilderSeed.Target
import ee.schimke.ha.designserver.UiBuilderSeed.TextRole
import ee.schimke.ha.designserver.UiBuilderSeed.float
import ee.schimke.ha.designserver.UiBuilderSeed.node
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The [UiBuilderSeed] retargeted to the `ha-cards` UI-builder catalog (`:ha-cards-catalog`): the
 * same card schemas, pinned to `ha-cards`, written in its component ids, and rooted on each of its
 * three design targets.
 *
 * - [card]: a dashboard card, the `ha-cards/dashboard-card` root (exported as inline
 *   `RemoteContent`).
 * - [wearSmall] / [wearLarge]: a Wear widget, rooted on the Wear widget containers (exported as a
 *   `GlanceWearWidget`).
 * - [launcher]: a phone launcher widget, rooted on `remote-widgets/launcher-widget` (exported as a
 *   `RemoteComposeWidget`).
 *
 * Two layouts fill them. [UiBuilderSeed.document] with one of these targets is the design server's
 * bound layout — text bound to the card's state variables — in `ha-cards/remote-text`. [haCard] is
 * the card itself: the `ha-cards` Home Assistant component for its type, its properties the
 * schema's example values and its entity, so the exported call keeps up with the entity live.
 *
 * `HaCardsTemplatesTest` writes the catalog's starting templates with these; ids here must stay the
 * ids `ha-cards-catalog/ui-builder.policy.json` declares, which that test also checks.
 */
object HaCardsSeed {
  const val CATALOG = "ha-cards"
  private const val REVISION = "candidate"

  const val DASHBOARD_CARD = "ha-cards/dashboard-card"
  const val DASHBOARD_SECTION = "ha-cards/dashboard-section"
  const val CARD_SURFACE = "ha-cards/card-surface"
  const val WEAR_SMALL = "remote-m3/widget-container-small"
  const val WEAR_LARGE = "remote-m3/widget-container-large"
  const val LAUNCHER_WIDGET = "remote-widgets/launcher-widget"

  const val TEXT = "ha-cards/remote-text"
  const val SWITCH = "ha-cards/remote-switch-button"
  const val TILE = "ha-cards/tile"
  const val ENTITY_ROW = "ha-cards/entity-row"
  const val GAUGE = "ha-cards/gauge"
  const val METRIC = "ha-cards/metric"
  const val THERMOMETER = "ha-cards/thermometer"
  const val BUTTON = "ha-cards/button"
  const val UBER_THEME = "ha-cards/uber-theme"
  const val CARD_TEMPLATE = "ha-cards/card-template"

  /** HA's default dark card background: a Wear widget's own background. */
  private const val WEAR_BACKGROUND = "#FF1C1C1C"
  /** HA's default light card background: a launcher widget's. */
  private const val LAUNCHER_BACKGROUND = "#FFFFFFFF"

  /**
   * `ha-cards/remote-text` has no type scale behind it here, so a role is a size, and a colour: on
   * a light card, HA's primary and secondary text, since the text's unset colour is Wear's light
   * content colour. A Wear widget's dark container keeps that unset colour.
   */
  private fun textStyle(light: Boolean): (TextRole) -> Map<String, JsonObject> = { role ->
    buildMap {
      put(
        "fontSize",
        float(
          when (role) {
            TextRole.Title -> 18.0
            TextRole.Name -> 15.0
            TextRole.State -> 13.0
            TextRole.Label -> 12.0
          }
        ),
      )
      if (light) {
        val secondary = role == TextRole.State || role == TextRole.Label
        put("color", UiBuilderSeed.string(if (secondary) "#FF8F8F8F" else "#FF141414"))
      }
    }
  }

  private fun target(
    template: String?,
    root: String,
    widthDp: Int?,
    heightDp: Int?,
    adaptive: Boolean,
    maxRows: Int = 8,
    title: Boolean = true,
    switches: Boolean = true,
    background: String? = null,
    light: Boolean = true,
  ) =
    Target(
      systemId = CATALOG,
      catalogRevision = REVISION,
      textComponent = TEXT,
      textStyle = textStyle(light),
      switchComponent = SWITCH.takeIf { switches },
      rootComponent = root,
      rootSlot = "content",
      rootProperties =
        background?.let { mapOf("background" to UiBuilderSeed.string(it)) }.orEmpty(),
      adaptive = adaptive,
      maxRows = maxRows,
      title = title,
      widthDp = widthDp,
      heightDp = heightDp,
      template = template,
    )

  /** A dashboard section's root, 360dp wide: its cards each sit on their own surface. */
  fun section(template: String?, heightDp: Int? = null) =
    target(template, DASHBOARD_SECTION, widthDp = 360, heightDp = heightDp, adaptive = true)

  /**
   * A dashboard card, 360dp wide, growing with its rows; adaptive like the design server's. Without
   * [title] an entities card leaves its heading out, as one inside a [section] does. A null
   * [template] is a design, not a starting template.
   */
  fun card(template: String?, heightDp: Int? = null, title: Boolean = true) =
    target(
      template,
      DASHBOARD_CARD,
      widthDp = 360,
      heightDp = heightDp,
      adaptive = true,
      title = title,
    )

  /** The Small Wear widget container, 216×76dp: room for one row and no title. */
  fun wearSmall(template: String) =
    target(
      template,
      WEAR_SMALL,
      widthDp = 216,
      heightDp = 76,
      adaptive = false,
      maxRows = 1,
      title = false,
      background = WEAR_BACKGROUND,
      light = false,
    )

  /** The Large Wear widget container, 216×124dp: a title and two rows. */
  fun wearLarge(template: String) =
    target(
      template,
      WEAR_LARGE,
      widthDp = 216,
      heightDp = 124,
      adaptive = false,
      maxRows = 2,
      background = WEAR_BACKGROUND,
      light = false,
    )

  /**
   * A launcher widget at [widthDp]×[heightDp] (a launcher grid size). Its body is HA components
   * only ([haCard]): the launcher exporter refuses Remote Material 3 calls, which `remote-text` and
   * `remote-switch-button` are.
   */
  fun launcher(template: String, widthDp: Int, heightDp: Int) =
    target(
      template,
      LAUNCHER_WIDGET,
      widthDp = widthDp,
      heightDp = heightDp,
      adaptive = false,
      switches = false,
      background = LAUNCHER_BACKGROUND,
    )

  /**
   * A dashboard section: a [heading] over [cards] in a column, the way Home Assistant's sections
   * view stacks a heading card and the cards under it. Each of [cards] is a document these seeds
   * wrote ([haCard], [measurement], [metric]); its body moves into the section on a [CARD_SURFACE]
   * of its own, with its node ids prefixed so two cards may share an id. [target] is a
   * [DASHBOARD_SECTION] root ([section] with a template id), which draws no surface, so each card
   * stays a card.
   */
  fun section(
    designId: String,
    title: String,
    target: Target,
    heading: String,
    cards: List<JsonObject>,
  ): JsonObject {
    val nodes = linkedMapOf<String, JsonObject>()
    nodes["root"] =
      node(
        "root",
        target.rootComponent,
        properties = target.rootProperties,
        modifiers = target.rootModifiers,
        slots = mapOf(target.rootSlot to listOf("section")),
      )
    val children = mutableListOf("heading")
    val surfaces = linkedMapOf<String, JsonObject>()
    val parts = linkedMapOf<String, JsonObject>()
    parts["heading"] =
      node(
        "heading",
        "ha-cards/heading",
        properties = mapOf("title" to UiBuilderSeed.string(heading)),
      )
    cards.forEachIndexed { index, card ->
      val prefix = "card$index-"
      val cardNodes = card.getValue("nodes").jsonObject
      val body =
        cardNodes.getValue("root").jsonObject.getValue("slots").jsonObject.values.flatMap {
          it.jsonArray.map { id -> prefix + id.jsonPrimitive.content }
        }
      val surface = "${prefix}surface"
      surfaces[surface] = node(surface, CARD_SURFACE, slots = mapOf("content" to body))
      children += surface
      for ((id, cardNode) in cardNodes) {
        if (id == "root") continue
        parts[prefix + id] = prefixed(cardNode.jsonObject, prefix)
      }
    }
    nodes["section"] =
      node(
        "section",
        "layout/column",
        properties = mapOf("verticalSpacingDp" to float(8.0)),
        modifiers = listOf(UiBuilderSeed.fillMaxWidth()),
        slots = mapOf("children" to children),
      )
    nodes.putAll(surfaces)
    nodes.putAll(parts)
    return UiBuilderSeed.envelope(
      target,
      designId,
      title,
      heightDp = target.heightDp ?: 480,
      stateVariables = emptyList(),
      nodes = nodes,
    )
  }

  /** [node] with its own id and every child id it names prefixed with [prefix]. */
  private fun prefixed(node: JsonObject, prefix: String): JsonObject =
    JsonObject(
      node.mapValues { (key, value) ->
        when (key) {
          "id" -> JsonPrimitive(prefix + value.jsonPrimitive.content)
          "slots" ->
            JsonObject(
              value.jsonObject.mapValues { (_, ids) ->
                JsonArray(ids.jsonArray.map { JsonPrimitive(prefix + it.jsonPrimitive.content) })
              }
            )
          else -> value
        }
      }
    )

  /** An editable sensor card made from the metric building block. */
  fun metric(
    designId: String,
    title: String,
    target: Target,
    label: String,
    value: String,
    unit: String,
    entityId: String,
  ): JsonObject {
    val nodes = linkedMapOf<String, JsonObject>()
    nodes["root"] =
      node(
        "root",
        target.rootComponent,
        properties = target.rootProperties,
        modifiers = target.rootModifiers,
        slots = mapOf(target.rootSlot to listOf("metric")),
      )
    nodes["metric"] =
      node(
        "metric",
        METRIC,
        properties =
          mapOf(
            "label" to UiBuilderSeed.string(label),
            "value" to UiBuilderSeed.string(value),
            "unit" to UiBuilderSeed.string(unit),
            "entityId" to UiBuilderSeed.string(entityId),
          ),
      )
    return UiBuilderSeed.envelope(
      target,
      designId,
      title,
      heightDp = target.heightDp ?: 120,
      stateVariables = emptyList(),
      nodes = nodes,
    )
  }

  /**
   * A typed measurement ([THERMOMETER] by default) alone in [target]'s root, at [presentation]:
   * `standard` for a dashboard card, `adaptive` for a widget cell, where it plays compact or
   * expanded by the cell's live size (card-component-system.md).
   */
  fun measurement(
    designId: String,
    title: String,
    target: Target,
    name: String,
    value: Double,
    entityId: String,
    presentation: String,
    componentId: String = THERMOMETER,
  ): JsonObject {
    val nodes = linkedMapOf<String, JsonObject>()
    nodes["root"] =
      node(
        "root",
        target.rootComponent,
        properties = target.rootProperties,
        modifiers = target.rootModifiers,
        slots = mapOf(target.rootSlot to listOf("measurement")),
      )
    nodes["measurement"] =
      node(
        "measurement",
        componentId,
        properties =
          mapOf(
            "name" to UiBuilderSeed.string(name),
            "value" to float(value),
            "entityId" to UiBuilderSeed.string(entityId),
            "presentation" to UiBuilderSeed.string(presentation),
          ),
      )
    return UiBuilderSeed.envelope(
      target,
      designId,
      title,
      heightDp = target.heightDp ?: 120,
      stateVariables = emptyList(),
      nodes = nodes,
    )
  }

  /**
   * A gauge's unit: the card's own `unit` (blank hides it), else what follows the number in the
   * entity's formatted state (`46 %` → `%`), which is where `unit_of_measurement` lands in a
   * [CardSchema].
   */
  internal fun gaugeUnit(card: JsonObject?, formattedState: String?): String? {
    // A configured unit wins outright, as in GaugeCardConverter: `""` hides the entity's unit.
    (card?.get("unit") as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.let {
        return it.content.trim().takeIf(String::isNotEmpty)
      }
    val match =
      Regex("""^\s*-?[0-9]+(?:[.,][0-9]+)?\s*([^0-9.,\s].*?)\s*$""")
        .find(formattedState ?: return null)
    return match?.groupValues?.get(1)?.takeIf(String::isNotEmpty)
  }

  /**
   * [schema] as the `ha-cards` Home Assistant component for its card type, in [target]'s root: a
   * `tile` for a tile card, a `gauge` for a gauge, a `button` for a button, and otherwise one
   * `entity-row` per slot (up to [Target.maxRows]) under an optional `heading`.
   */
  fun haCard(
    schema: CardSchema,
    designId: String,
    title: String,
    target: Target,
    entityOf: (SlotSchema) -> String? = { it.entity },
    stateVariables: List<Pair<String, JsonObject>> = emptyList(),
  ): JsonObject {
    val examples = schema.bindings.associate { it.name to it.example }
    fun text(name: String): String? =
      examples[name]?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
    val nodes = LinkedHashMap<String, JsonObject>()
    val slots = schema.slots.take(target.maxRows)

    fun entityProperties(slot: SlotSchema): Map<String, JsonObject> = buildMap {
      put("name", UiBuilderSeed.string(text("${slot.slot}.name") ?: slot.name ?: slot.slot))
      put("state", UiBuilderSeed.string(text("${slot.slot}.state") ?: ""))
      entityOf(slot)?.let { put("entityId", UiBuilderSeed.string(it)) }
      put("icon", UiBuilderSeed.string(iconFor(slot.domain)))
      if (UiBuilderSeed.toggleable(slot.domain)) {
        put("toggleable", bool(true))
        examples["${slot.slot}.is_on"]?.booleanOrNull?.let { put("isOn", bool(it)) }
      }
    }

    val body: String
    val first = slots.firstOrNull()
    when {
      schema.cardType == "gauge" && first != null -> {
        val value = examples["${first.slot}.numeric_state"]?.doubleOrNull ?: 0.0
        val card = schema.card
        nodes["gauge"] =
          node(
            "gauge",
            GAUGE,
            properties =
              buildMap {
                put("name", UiBuilderSeed.string(text("${first.slot}.name") ?: first.slot))
                put("value", float(value))
                (card?.get("min") as? JsonPrimitive)?.doubleOrNull?.let { put("min", float(it)) }
                (card?.get("max") as? JsonPrimitive)?.doubleOrNull?.let { put("max", float(it)) }
                gaugeUnit(card, text("${first.slot}.state"))?.let {
                  put("unit", UiBuilderSeed.string(it))
                }
                entityOf(first)?.let { put("entityId", UiBuilderSeed.string(it)) }
              },
          )
        body = "gauge"
      }
      schema.cardType in setOf("tile", "entity") && first != null -> {
        nodes["tile"] = node("tile", TILE, properties = entityProperties(first))
        body = "tile"
      }
      schema.cardType == "button" && first != null -> {
        nodes["button"] = node("button", BUTTON, properties = entityProperties(first) - "state")
        body = "button"
      }
      else -> {
        val children = mutableListOf<String>()
        val heading = text("card.title")?.takeIf { target.title }
        if (heading != null) {
          nodes["heading"] =
            node(
              "heading",
              "ha-cards/heading",
              properties =
                mapOf("title" to UiBuilderSeed.string(heading), "subtitle" to bool(true)),
            )
          children += "heading"
        }
        for (slot in slots) {
          val id = "row${slot.index}"
          nodes[id] =
            node(
              id,
              ENTITY_ROW,
              properties = entityProperties(slot),
              modifiers = listOf(UiBuilderSeed.fillMaxWidth()),
            )
          children += id
        }
        nodes["column"] =
          node(
            "column",
            "layout/column",
            properties = mapOf("verticalSpacingDp" to float(4.0)),
            modifiers = listOf(UiBuilderSeed.fillMaxWidth()),
            slots = mapOf("children" to children),
          )
        body = "column"
      }
    }
    // The root goes first, as in the design server's documents.
    val ordered = LinkedHashMap<String, JsonObject>()
    ordered["root"] =
      node(
        "root",
        target.rootComponent,
        properties = target.rootProperties,
        modifiers = target.rootModifiers,
        slots = mapOf(target.rootSlot to listOf(body)),
      )
    ordered.putAll(nodes)
    return UiBuilderSeed.envelope(
      target,
      designId,
      title,
      heightDp = target.heightDp ?: 120,
      stateVariables = stateVariables,
      nodes = ordered,
    )
  }

  /** An `ha-cards/uber-theme` choice: one of `HaUberTheme.all`'s ids, light or [dark]. */
  data class UberTheme(val id: String, val dark: Boolean)

  /**
   * The uber theme a library design is drawn in, from its key's theme: `style:<id>:<mode>` is that
   * card style, a baked theme (`baked:<app style>:<mode>`) is the cards' own `classic` look.
   */
  fun uberThemeOf(key: DesignKey): UberTheme? {
    val parts = key.theme.split(':')
    if (parts.size != 3) return null
    val dark = parts[2] == "dark"
    return when (parts[0]) {
      "style" -> UberTheme(parts[1], dark)
      "baked" -> UberTheme("classic", dark)
      else -> null
    }
  }

  /**
   * The editable source of library [design]: a UI Builder document (`.uid`) to fork it from.
   *
   * The library's templates are captured from the app's card converters, so there is no source to
   * recover from them; this rebuilds the card in `ha-cards` components ([haCard]). It nests, from
   * the root down:
   * - a [DASHBOARD_SECTION] root, which draws no surface of its own;
   * - a [CARD_TEMPLATE], so names and titles bind as the library template's do (`light.s0.name`,
   *   `card.title`) rather than being the example's text;
   * - the design's [UBER_THEME], around the surface too, so its background and corners are the
   *   theme's;
   * - a [CARD_SURFACE] and the card's body, its entities named by slot (`light.s0`).
   *
   * So the exported card binds the `<domain>.s<N>.*` and `card.*` values the library template does,
   * and a fork published over it keeps up with Home Assistant. Every binding is declared as a state
   * variable, so the editor lists the fields there are to bind. It is close to the library design,
   * not identical: the converters and the catalog components differ in detail.
   */
  fun source(library: DesignLibrary, design: LibraryDesign): JsonObject {
    val schema = CardSchema.fromDesign(library, design)
    val theme = uberThemeOf(design.key)
    val card =
      haCard(
        schema,
        designId = sourceId(design),
        title =
          "${design.key.cardType}${theme?.let { " · ${it.id}${if (it.dark) " dark" else ""}" } ?: ""}",
        target = section(template = null),
        entityOf = { it.slot },
        stateVariables = UiBuilderSeed.stateVariables(schema),
      )
    val nodes = card.getValue("nodes").jsonObject
    val root = nodes.getValue("root").jsonObject
    val body = root.getValue("slots").jsonObject.getValue("content").jsonArray
    val wrapped = LinkedHashMap<String, JsonObject>()
    wrapped["root"] =
      JsonObject(
        root +
          ("slots" to JsonObject(mapOf("content" to JsonArray(listOf(JsonPrimitive("template"))))))
      )
    val inTheme = if (theme != null) "theme" else "surface"
    wrapped["template"] =
      node(
        "template",
        CARD_TEMPLATE,
        modifiers = listOf(UiBuilderSeed.fillMaxWidth()),
        slots = mapOf("children" to listOf(inTheme)),
      )
    if (theme != null) {
      wrapped["theme"] =
        node(
          "theme",
          UBER_THEME,
          properties = mapOf("theme" to UiBuilderSeed.string(theme.id), "dark" to bool(theme.dark)),
          modifiers = listOf(UiBuilderSeed.fillMaxWidth()),
          slots = mapOf("children" to listOf("surface")),
        )
    }
    wrapped["surface"] =
      node(
        "surface",
        CARD_SURFACE,
        slots = mapOf("content" to body.map { it.jsonPrimitive.content }),
      )
    for ((id, child) in nodes) if (id != "root") wrapped[id] = child.jsonObject
    return JsonObject(card + ("nodes" to JsonObject(wrapped)))
  }

  /** A path-safe UI Builder id for a fork of [design], unique per call. */
  fun forkId(design: LibraryDesign): String =
    "${sourceId(design)}-${System.currentTimeMillis().toString(36)}"

  private fun sourceId(design: LibraryDesign): String {
    val type = design.key.cardType.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
    return "card-$type-${design.id.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(24)}"
  }

  /**
   * The `ha-cards` icon key for an entity [domain]. Every key must be one `HaCardIcons` draws,
   * which the policy's `icon` allowed values list and `HaCardsTemplatesTest` checks.
   */
  fun iconFor(domain: String): String =
    when (domain) {
      "light" -> "light"
      "switch",
      "input_boolean",
      "automation" -> "switch"
      "climate",
      "water_heater" -> "climate"
      "sensor",
      "binary_sensor" -> "sensor"
      "fan" -> "fan"
      "lock" -> "lock"
      "cover" -> "door"
      "media_player" -> "media"
      "alarm_control_panel",
      "siren" -> "alert"
      else -> "home"
    }

  private fun bool(value: Boolean) = buildJsonObject {
    put("type", "bool")
    put("value", value)
  }
}
