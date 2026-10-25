package ee.schimke.ha.client.dashboard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One setting a card's form shows: a Lovelace key of the card, and how to edit it. The form covers
 * the settings people change most; everything else stays reachable as JSON, and keys the form
 * doesn't know are kept untouched.
 */
sealed interface CardField {
  val key: String
  val label: String

  data class Text(
    override val key: String,
    override val label: String,
    val multiline: Boolean = false,
  ) : CardField

  data class Number(override val key: String, override val label: String) : CardField

  /** A switch; [default] is what Home Assistant assumes when the key is absent. */
  data class Toggle(override val key: String, override val label: String, val default: Boolean) :
    CardField

  /** One of [options] (value to label); the empty value is Home Assistant's default. */
  data class Choice(
    override val key: String,
    override val label: String,
    val options: List<Pair<String, String>>,
  ) : CardField

  /** The card's one entity (`entity`). */
  data class Entity(override val label: String = "Entity") : CardField {
    override val key: String = "entity"
  }

  /** The card's `entities` rows. */
  data class Entities(override val label: String = "Entities") : CardField {
    override val key: String = "entities"
  }

  /** What tapping does (`tap_action`), from the actions a form can set without more settings. */
  data class TapAction(override val label: String = "Tap action") : CardField {
    override val key: String = "tap_action"
  }
}

/**
 * The settings form of each card type ([fields]) and the edits it makes. Each edit returns the card
 * with one key changed, removing the key when it goes back to blank or to Home Assistant's default,
 * so a config edited by form stays as small as one written by hand.
 */
object CardSettings {
  private val name = CardField.Text("name", "Name")
  private val title = CardField.Text("title", "Title")
  private val icon = CardField.Text("icon", "Icon (mdi:…)")
  private val entity = CardField.Entity()
  private val entities = CardField.Entities()
  private val tap = CardField.TapAction()
  private val hours = CardField.Number("hours_to_show", "Hours to show")

  /**
   * The actions [CardField.TapAction] offers: the default, and those that need no more settings.
   */
  val TAP_ACTIONS: List<Pair<String, String>> =
    listOf(
      "" to "Default",
      "more-info" to "Show details",
      "toggle" to "Toggle",
      "none" to "Nothing",
    )

  /** The form for [card]'s type; empty for a type the form doesn't know (JSON only). */
  fun fields(card: JsonObject): List<CardField> =
    when (card.string("type")) {
      "tile" ->
        listOf(
          entity,
          name,
          icon,
          CardField.Toggle("vertical", "Vertical", default = false),
          CardField.Toggle("hide_state", "Hide state", default = false),
          tap,
        )
      "button" ->
        listOf(
          entity,
          name,
          icon,
          CardField.Toggle("show_name", "Show name", default = true),
          CardField.Toggle("show_icon", "Show icon", default = true),
          CardField.Toggle("show_state", "Show state", default = false),
          tap,
        )
      "entity" ->
        listOf(
          entity,
          name,
          icon,
          CardField.Text("attribute", "Attribute"),
          CardField.Text("unit", "Unit"),
        )
      "sensor" ->
        listOf(
          entity,
          name,
          icon,
          CardField.Choice("graph", "Graph", listOf("" to "None", "line" to "Line")),
          hours,
        )
      "gauge" ->
        listOf(
          entity,
          name,
          CardField.Text("unit", "Unit"),
          CardField.Number("min", "Minimum"),
          CardField.Number("max", "Maximum"),
          CardField.Toggle("needle", "Needle", default = false),
        )
      "statistic" ->
        listOf(
          entity,
          name,
          CardField.Choice(
            "stat_type",
            "Statistic",
            listOf("" to "Change", "max" to "Maximum", "min" to "Minimum", "mean" to "Mean"),
          ),
        )
      "thermostat",
      "humidifier",
      "light",
      "media-control",
      "alarm-panel",
      "weather-forecast",
      "picture-entity" -> listOf(entity, name)
      "todo-list" -> listOf(entity, title)
      "entities" ->
        listOf(
          title,
          entities,
          CardField.Toggle("show_header_toggle", "Header toggle", default = true),
        )
      "glance" ->
        listOf(
          title,
          entities,
          CardField.Toggle("show_name", "Show names", default = true),
          CardField.Toggle("show_state", "Show states", default = true),
          CardField.Number("columns", "Columns"),
        )
      "history-graph",
      "logbook" -> listOf(title, entities, hours)
      "statistics-graph" ->
        listOf(title, entities, CardField.Number("days_to_show", "Days to show"))
      "map" -> listOf(title, entities, hours, CardField.Number("default_zoom", "Zoom"))
      "calendar" ->
        listOf(
          title,
          entities,
          CardField.Choice(
            "initial_view",
            "View",
            listOf("" to "Month", "listWeek" to "Week list", "dayGridDay" to "Day"),
          ),
        )
      "heading" ->
        listOf(
          CardField.Text("heading", "Heading"),
          CardField.Choice(
            "heading_style",
            "Style",
            listOf("" to "Title", "subtitle" to "Subtitle"),
          ),
          icon,
        )
      "markdown" -> listOf(title, CardField.Text("content", "Text (Markdown)", multiline = true))
      "clock" ->
        listOf(
          CardField.Choice(
            "clock_size",
            "Size",
            listOf("" to "Small", "medium" to "Medium", "large" to "Large"),
          ),
          CardField.Choice(
            "time_format",
            "Format",
            listOf("" to "Automatic", "12" to "12 hour", "24" to "24 hour"),
          ),
          CardField.Toggle("show_seconds", "Seconds", default = false),
        )
      else -> emptyList()
    }

  /** A text or choice setting as the form shows it: "" when unset. */
  fun text(card: JsonObject, key: String): String =
    when (val value = card[key]) {
      is JsonPrimitive -> value.contentOrNull.orEmpty()
      else -> ""
    }

  /** [card] with [key] set to [value], or without it when [value] is blank. */
  fun withText(card: JsonObject, key: String, value: String): JsonObject =
    if (value.isBlank()) JsonObject(card - key) else card.with(key, JsonPrimitive(value))

  /** [card] with [key] set to a number from [value] (whole numbers stay whole), or without it. */
  fun withNumber(card: JsonObject, key: String, value: String): JsonObject {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return JsonObject(card - key)
    val number = trimmed.toDoubleOrNull() ?: throw IllegalArgumentException("Not a number.")
    val element =
      if (number == kotlin.math.floor(number) && kotlin.math.abs(number) < 1e15)
        JsonPrimitive(number.toLong())
      else JsonPrimitive(number)
    return card.with(key, element)
  }

  /** Whether a number setting's [value] can be saved: blank, or a number. */
  fun isNumber(value: String): Boolean = value.isBlank() || value.trim().toDoubleOrNull() != null

  fun toggle(card: JsonObject, field: CardField.Toggle): Boolean =
    (card[field.key] as? JsonPrimitive)?.booleanOrNull ?: field.default

  /** [card] with [field] switched to [on]; back at its default the key is removed. */
  fun withToggle(card: JsonObject, field: CardField.Toggle, on: Boolean): JsonObject =
    if (on == field.default) JsonObject(card - field.key)
    else card.with(field.key, JsonPrimitive(on))

  /** The ids of [card]'s `entities` rows, whether a row is an id or an `{entity}` object. */
  fun entityIds(card: JsonObject): List<String> =
    (card["entities"] as? JsonArray).orEmpty().map { row ->
      when (row) {
        is JsonPrimitive -> row.contentOrNull.orEmpty()
        is JsonObject -> (row["entity"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        else -> ""
      }
    }

  /** [card] with [entityId] appended to its `entities`. */
  fun withEntityAdded(card: JsonObject, entityId: String): JsonObject =
    card.with("entities", JsonArray(rows(card) + JsonPrimitive(entityId)))

  /** [card] without its `entities` row [index]; the other rows keep their own settings. */
  fun withEntityRemoved(card: JsonObject, index: Int): JsonObject {
    val all = rows(card)
    require(index in all.indices) { "No entity row $index" }
    return card.with("entities", JsonArray(all.filterIndexed { i, _ -> i != index }))
  }

  /** [card]'s `entities` row [index] moved [by] places, clamped. */
  fun withEntityMoved(card: JsonObject, index: Int, by: Int): JsonObject {
    val all = rows(card).toMutableList()
    require(index in all.indices) { "No entity row $index" }
    all.add((index + by).coerceIn(0, all.lastIndex), all.removeAt(index))
    return card.with("entities", JsonArray(all))
  }

  /** The tap action [card] sets: "" for the default. */
  fun tapAction(card: JsonObject): String =
    ((card["tap_action"] as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull.orEmpty()

  /**
   * [card] tapping to [action] ("" for the default). An action set elsewhere with more settings (a
   * navigation path, a service) keeps them while it stays the same action.
   */
  fun withTapAction(card: JsonObject, action: String): JsonObject {
    if (action.isBlank()) return JsonObject(card - "tap_action")
    if (action == tapAction(card)) return card
    return card.with("tap_action", JsonObject(mapOf("action" to JsonPrimitive(action))))
  }

  /** The tap actions to offer for [card]: [TAP_ACTIONS], and its own when it is another. */
  fun tapActions(card: JsonObject): List<Pair<String, String>> {
    val own = tapAction(card)
    return if (own.isEmpty() || TAP_ACTIONS.any { it.first == own }) TAP_ACTIONS
    else TAP_ACTIONS + (own to own)
  }

  /** The options to offer for [field]: its own, and [card]'s value when it is another. */
  fun choices(card: JsonObject, field: CardField.Choice): List<Pair<String, String>> {
    val own = text(card, field.key)
    return if (own.isEmpty() || field.options.any { it.first == own }) field.options
    else field.options + (own to own)
  }

  private fun rows(card: JsonObject): List<JsonElement> = (card["entities"] as? JsonArray).orEmpty()

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

  /** A number setting as the form shows it. */
  fun number(card: JsonObject, key: String): String {
    val value = card[key] as? JsonPrimitive ?: return ""
    val d = value.doubleOrNull ?: return value.contentOrNull.orEmpty()
    return if (d == kotlin.math.floor(d)) d.toLong().toString() else d.toString()
  }
}
