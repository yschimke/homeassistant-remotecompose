package ee.schimke.ha.designserver

import ee.schimke.ha.designserver.UiBuilderSeed.Target
import ee.schimke.ha.designserver.UiBuilderSeed.TextRole
import ee.schimke.ha.designserver.UiBuilderSeed.float
import ee.schimke.ha.designserver.UiBuilderSeed.node
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
  const val WEAR_SMALL = "remote-m3/widget-container-small"
  const val WEAR_LARGE = "remote-m3/widget-container-large"
  const val LAUNCHER_WIDGET = "remote-widgets/launcher-widget"

  const val TEXT = "ha-cards/remote-text"
  const val SWITCH = "ha-cards/remote-switch-button"
  const val TILE = "ha-cards/tile"
  const val ENTITY_ROW = "ha-cards/entity-row"
  const val GAUGE = "ha-cards/gauge"
  const val BUTTON = "ha-cards/button"

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
    template: String,
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

  /** A dashboard card, 360dp wide, growing with its rows; adaptive like the design server's. */
  fun card(template: String, heightDp: Int? = null) =
    target(template, DASHBOARD_CARD, widthDp = 360, heightDp = heightDp, adaptive = true)

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
   * A gauge's unit: the card's own `unit`, else what follows the number in the entity's formatted
   * state (`46 %` → `%`), which is where `unit_of_measurement` lands in a [CardSchema].
   */
  internal fun gaugeUnit(card: JsonObject?, formattedState: String?): String? {
    (card?.get("unit") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let {
      return it
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
  fun haCard(schema: CardSchema, designId: String, title: String, target: Target): JsonObject {
    val examples = schema.bindings.associate { it.name to it.example }
    fun text(name: String): String? =
      examples[name]?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
    val nodes = LinkedHashMap<String, JsonObject>()
    val slots = schema.slots.take(target.maxRows)

    fun entityProperties(slot: SlotSchema): Map<String, JsonObject> = buildMap {
      put("name", UiBuilderSeed.string(text("${slot.slot}.name") ?: slot.name ?: slot.slot))
      put("state", UiBuilderSeed.string(text("${slot.slot}.state") ?: ""))
      slot.entity?.let { put("entityId", UiBuilderSeed.string(it)) }
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
                first.entity?.let { put("entityId", UiBuilderSeed.string(it)) }
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
      stateVariables = emptyList(),
      nodes = ordered,
    )
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
