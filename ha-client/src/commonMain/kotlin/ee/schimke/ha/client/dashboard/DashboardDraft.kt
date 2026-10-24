package ee.schimke.ha.client.dashboard

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.SectionCardGrid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Where a card sits in a dashboard config: its view, its section in a `sections` view (null for a
 * masonry view's `cards`), and its index there.
 */
data class CardAddress(val view: Int, val section: Int?, val card: Int)

/**
 * Edits to a Lovelace dashboard config as Home Assistant stores it, for the app's dashboard editor.
 * Every edit returns a new config and touches only what it names: unknown view, section and card
 * fields (custom cards included) pass through untouched, so a saved draft never loses what harc
 * can't draw. An address outside the config is an [IllegalArgumentException].
 *
 * Sizes are HA's section grid ([SectionCardGrid]): a card's `grid_options` `columns` (of 12 per
 * section column) and `rows` (fixed 56 dp rows, or `auto`). That is what the size means wherever
 * the dashboard is shown in Home Assistant, so it is what the editor writes.
 */
object DashboardDraft {
  /** The column widths the editor steps through: quarter, half, three-quarter and full. */
  val COLUMN_STEPS: List<Int> = listOf(3, 6, 9, 12)

  /** The most fixed rows the editor offers before `auto`. */
  const val MAX_ROWS: Int = 8

  /** A new dashboard's config: one sections view holding one empty section. */
  fun newDashboard(title: String): JsonObject = buildJsonObject {
    put("title", title)
    put(
      "views",
      JsonArray(listOf(newView(title.ifBlank { "Home" }, path = null, sectionTitle = null))),
    )
  }

  private fun newView(title: String, path: String?, sectionTitle: String?) = buildJsonObject {
    put("title", title)
    if (path != null) put("path", path)
    put("type", "sections")
    put("max_columns", 4)
    put("sections", JsonArray(listOf(newSection(sectionTitle))))
  }

  private fun newSection(title: String?) = buildJsonObject {
    put("type", "grid")
    put("cards", JsonArray(listOfNotNull(title?.takeIf { it.isNotBlank() }?.let(::heading))))
  }

  /** A `heading` card, how HA titles a section. */
  fun heading(title: String): JsonObject = buildJsonObject {
    put("type", "heading")
    put("heading", title)
  }

  fun views(config: JsonObject): List<JsonObject> = config.array("views").map { it as JsonObject }

  /** Whether view [view] lays its cards out in sections. */
  fun isSections(config: JsonObject, view: Int): Boolean =
    views(config)[view].string("type") == "sections"

  /** The sections of view [view]; empty for a masonry view. */
  fun sections(config: JsonObject, view: Int): List<JsonObject> =
    views(config)[view].array("sections").mapNotNull { it as? JsonObject }

  /** The cards of section [section] of view [view], or the view's own cards for a null section. */
  fun cards(config: JsonObject, view: Int, section: Int?): List<JsonObject> {
    val holder = if (section == null) views(config)[view] else sections(config, view)[section]
    return holder.array("cards").mapNotNull { it as? JsonObject }
  }

  fun card(config: JsonObject, at: CardAddress): JsonObject? = runCatching {
    cards(config, at.view, at.section).getOrNull(at.card)
  }
    .getOrNull()

  /** A view titled [title] appended after the last, with one empty section. */
  fun addView(config: JsonObject, title: String): JsonObject {
    val taken = views(config).mapNotNull { it.string("path") }.toSet()
    val base = slug(title).ifEmpty { "view" }
    val path =
      generateSequence(1) { it + 1 }
        .map { if (it == 1) base else "$base-$it" }
        .first { it !in taken }
    return config.with(
      "views",
      JsonArray(views(config) + newView(title, path, sectionTitle = null)),
    )
  }

  /** View [view] titled [title]; its `path` stays, so links to the view keep working. */
  fun renameView(config: JsonObject, view: Int, title: String): JsonObject {
    require(title.isNotBlank()) { "A view needs a title." }
    return updateView(config, view) { it.with("title", JsonPrimitive(title.trim())) }
  }

  /** Remove view [view]; a dashboard always keeps at least one. */
  fun removeView(config: JsonObject, view: Int): JsonObject {
    val all = views(config)
    require(view in all.indices) { "No view $view" }
    require(all.size > 1) { "A dashboard needs at least one view." }
    return config.with("views", JsonArray(all.filterIndexed { i, _ -> i != view }))
  }

  /** A section titled [title] (by a heading card) appended to view [view]. */
  fun addSection(config: JsonObject, view: Int, title: String?): JsonObject =
    updateView(config, view) { v ->
      require(v.string("type") == "sections") { "Only a sections view holds sections." }
      v.with("sections", JsonArray(v.array("sections") + newSection(title)))
    }

  fun removeSection(config: JsonObject, view: Int, section: Int): JsonObject =
    updateView(config, view) { v ->
      val all = v.array("sections")
      require(section in all.indices) { "No section $section" }
      v.with("sections", JsonArray(all.filterIndexed { i, _ -> i != section }))
    }

  /** Move section [section] of view [view] [by] places (negative is earlier), clamped. */
  fun moveSection(config: JsonObject, view: Int, section: Int, by: Int): JsonObject =
    updateView(config, view) { v ->
      v.with("sections", JsonArray(v.array("sections").moved(section, by)))
    }

  /**
   * Insert [card] into section [section] of view [view] at [index] (the end when null), returning
   * the config and where the card now is.
   */
  fun addCard(
    config: JsonObject,
    view: Int,
    section: Int?,
    card: JsonObject,
    index: Int? = null,
  ): Pair<JsonObject, CardAddress> {
    var at: CardAddress? = null
    val updated =
      updateCards(config, view, section) { cards ->
        val i = (index ?: cards.size).coerceIn(0, cards.size)
        at = CardAddress(view, section, i)
        cards.toMutableList().apply { add(i, card) }
      }
    return updated to at!!
  }

  fun replaceCard(config: JsonObject, at: CardAddress, card: JsonObject): JsonObject =
    updateCards(config, at.view, at.section) { cards ->
      require(at.card in cards.indices) { "No card ${at.card}" }
      cards.toMutableList().apply { set(at.card, card) }
    }

  fun removeCard(config: JsonObject, at: CardAddress): JsonObject =
    updateCards(config, at.view, at.section) { cards ->
      require(at.card in cards.indices) { "No card ${at.card}" }
      cards.filterIndexed { i, _ -> i != at.card }
    }

  /** Move a card [by] places within its section (negative is earlier), clamped to the section. */
  fun moveCard(config: JsonObject, at: CardAddress, by: Int): Pair<JsonObject, CardAddress> {
    var to = at
    val updated =
      updateCards(config, at.view, at.section) { cards ->
        require(at.card in cards.indices) { "No card ${at.card}" }
        to = at.copy(card = (at.card + by).coerceIn(0, cards.lastIndex))
        cards.moved(at.card, by)
      }
    return updated to to
  }

  /**
   * Move a card to the end of another section of the same view, returning where it lands. Sections
   * views only.
   */
  fun moveCardToSection(
    config: JsonObject,
    at: CardAddress,
    section: Int,
  ): Pair<JsonObject, CardAddress> {
    val card = card(config, at) ?: throw IllegalArgumentException("No card ${at.card}")
    if (at.section == section) return config to at
    return addCard(removeCard(config, at), at.view, section, card)
  }

  /**
   * The size HA gives [card] in a one-column section: columns of 12 and fixed rows (null for
   * `auto`), with the card type's defaults filled in and its limits applied.
   */
  fun size(card: JsonObject): CardSize {
    val config = card.toCardConfig()
    val options = SectionCardGrid.gridOptions(config)
    val size = SectionCardGrid.size(config)
    return CardSize(
      columns = size.columns ?: SectionCardGrid.BASE_COLUMNS,
      rows = size.rows,
      minColumns = (options.minColumns ?: 1).coerceIn(1, SectionCardGrid.BASE_COLUMNS),
      maxColumns =
        (options.maxColumns ?: SectionCardGrid.BASE_COLUMNS).coerceIn(
          1,
          SectionCardGrid.BASE_COLUMNS,
        ),
      minRows = options.minRows ?: 1,
      maxRows = options.maxRows ?: MAX_ROWS,
    )
  }

  /**
   * Give the card at [at] [columns] of 12 and [rows] fixed rows (null for `auto`), clamped to its
   * type's limits, written as `grid_options` (replacing a legacy `layout_options`). Other
   * `grid_options` keys stay.
   */
  fun resizeCard(config: JsonObject, at: CardAddress, columns: Int, rows: Int?): JsonObject {
    val card = card(config, at) ?: throw IllegalArgumentException("No card ${at.card}")
    return replaceCard(config, at, resized(card, columns, rows))
  }

  /** [card] with its `grid_options` set to [columns] and [rows], as [resizeCard] writes them. */
  fun resized(card: JsonObject, columns: Int, rows: Int?): JsonObject {
    val limits = size(card)
    val c = columns.coerceIn(limits.minColumns, limits.maxColumns)
    val r = rows?.coerceIn(limits.minRows, limits.maxRows.coerceAtLeast(limits.minRows))
    val existing = card["grid_options"] as? JsonObject ?: JsonObject(emptyMap())
    val options =
      JsonObject(
        existing +
          mapOf(
            "columns" to
              (if (c >= SectionCardGrid.BASE_COLUMNS) JsonPrimitive("full") else JsonPrimitive(c)),
            "rows" to (r?.let(::JsonPrimitive) ?: JsonPrimitive("auto")),
          )
      )
    return JsonObject(card - "layout_options" + ("grid_options" to options))
  }

  /**
   * The next wider (or narrower, for a negative [direction]) [COLUMN_STEPS] width from [columns].
   */
  fun stepColumns(columns: Int, direction: Int): Int =
    if (direction > 0) COLUMN_STEPS.firstOrNull { it > columns } ?: COLUMN_STEPS.last()
    else COLUMN_STEPS.lastOrNull { it < columns } ?: COLUMN_STEPS.first()

  /**
   * A new card for [entityId]: [type] when given, otherwise the card type harc draws best for the
   * entity's domain ([suggestedTypes]' first).
   */
  fun cardFor(entityId: String, type: String? = null): JsonObject = buildJsonObject {
    val chosen = type ?: suggestedTypes(entityId).first()
    put("type", chosen)
    when (chosen) {
      "history-graph",
      "statistics-graph",
      "entities",
      "glance",
      "logbook",
      "map",
      "calendar" -> put("entities", JsonArray(listOf(JsonPrimitive(entityId))))
      else -> put("entity", entityId)
    }
  }

  /**
   * [card] showing [entityId] instead, with the rest of its config (size, features, actions, name,
   * visibility) kept: its `entity`, or the first of its `entities` (a string or an `{entity}` row).
   * A card with neither gets an `entity`.
   */
  fun withEntity(card: JsonObject, entityId: String): JsonObject {
    val entities = card["entities"] as? JsonArray
    if ("entity" in card || entities == null) {
      return JsonObject(card + ("entity" to JsonPrimitive(entityId)))
    }
    val first: JsonElement =
      when (val row = entities.firstOrNull()) {
        is JsonObject -> JsonObject(row + ("entity" to JsonPrimitive(entityId)))
        else -> JsonPrimitive(entityId)
      }
    return JsonObject(card + ("entities" to JsonArray(listOf(first) + entities.drop(1))))
  }

  /**
   * The one entity [card] shows: its `entity`, or the single row of its `entities`. Null for a card
   * showing several entities or none, which has no template to swap.
   */
  fun primaryEntity(card: JsonObject): String? {
    (card["entity"] as? JsonPrimitive)?.contentOrNull?.let {
      return it.takeIf { "entities" !in card }
    }
    val rows = card["entities"] as? JsonArray ?: return null
    val row = rows.singleOrNull() ?: return null
    return (row as? JsonPrimitive)?.contentOrNull
      ?: ((row as? JsonObject)?.get("entity") as? JsonPrimitive)?.contentOrNull
  }

  /**
   * The card types [card] can switch to: types harc draws for its one entity ([suggestedTypes]),
   * other than its own. Empty for a card showing several entities or none.
   */
  fun templateChoices(card: JsonObject): List<String> {
    val entity = primaryEntity(card) ?: return emptyList()
    val current = (card["type"] as? JsonPrimitive)?.contentOrNull
    return suggestedTypes(entity).filter { it != current && it != "entities" }
  }

  /**
   * [card] drawn as [type] instead: its entity in the shape [type] takes, with [SHARED_KEYS] kept
   * (name, icon, size, visibility, actions) and every key specific to the old type dropped, since
   * it means nothing, or something else, to the new one. A kept size, legacy `layout_options`
   * included, is clamped to the new type's limits.
   */
  fun withType(card: JsonObject, type: String): JsonObject {
    val entity =
      primaryEntity(card)
        ?: throw IllegalArgumentException("Only a card showing one entity can change its template.")
    val base = cardFor(entity, type)
    val kept = card.filterKeys { it in SHARED_KEYS }
    val swapped = JsonObject(base + kept)
    return when {
      "grid_options" in kept -> size(swapped).let { resized(swapped, it.columns, it.rows) }
      // The older `layout_options` is specific to no type but isn't kept as is: its size moves to
      // `grid_options`, as a resize would write it.
      "layout_options" in card -> size(card).let { resized(swapped, it.columns, it.rows) }
      else -> swapped
    }
  }

  /** Card keys that mean the same on every card type, kept by [withType]. */
  val SHARED_KEYS: Set<String> =
    setOf(
      "name",
      "icon",
      "grid_options",
      "visibility",
      "tap_action",
      "hold_action",
      "double_tap_action",
    )

  /** Card types that suit [entityId]'s domain, best first; always ends with the generic ones. */
  fun suggestedTypes(entityId: String): List<String> {
    val domain = entityId.substringBefore('.')
    val specific =
      when (domain) {
        "climate" -> listOf("thermostat", "tile")
        "humidifier" -> listOf("humidifier", "tile")
        "light" -> listOf("tile", "light", "button")
        "switch",
        "input_boolean",
        "fan",
        "lock",
        "cover",
        "valve",
        "vacuum",
        "lawn_mower" -> listOf("tile", "button")
        "script",
        "scene",
        "button",
        "input_button" -> listOf("button", "tile")
        "media_player" -> listOf("media-control", "tile")
        "weather" -> listOf("weather-forecast", "tile")
        "alarm_control_panel" -> listOf("alarm-panel", "tile")
        "camera",
        "image" -> listOf("picture-entity")
        "todo" -> listOf("todo-list")
        "calendar" -> listOf("calendar")
        "person",
        "device_tracker",
        "zone" -> listOf("tile", "map")
        "sensor",
        "number",
        "input_number",
        "counter" -> listOf("tile", "sensor", "gauge", "history-graph", "statistic")
        "binary_sensor" -> listOf("tile", "history-graph")
        else -> listOf("tile")
      }
    return (specific + listOf("tile", "entity", "entities", "history-graph")).distinct()
  }

  /** Card types the editor offers to add without an entity. */
  val ENTITYLESS_TYPES: List<String> = listOf("heading", "markdown", "clock")

  /** A new card of an [ENTITYLESS_TYPES] type, with placeholder content to edit. */
  fun entitylessCard(type: String): JsonObject = buildJsonObject {
    put("type", type)
    when (type) {
      "heading" -> put("heading", "New heading")
      "markdown" -> put("content", "New text")
    }
  }

  /**
   * A dashboard `url_path` for [title]: HA requires a hyphen in it, so a one-word title gets a
   * `dashboard-` prefix; [taken] paths get a numeric suffix.
   */
  fun urlPathFor(title: String, taken: Set<String?>): String {
    val s = slug(title).ifEmpty { "home" }
    val base = if ('-' in s) s else "dashboard-$s"
    return generateSequence(1) { it + 1 }
      .map { if (it == 1) base else "$base-$it" }
      .first { it !in taken }
  }

  private fun slug(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

  private fun updateView(
    config: JsonObject,
    view: Int,
    edit: (JsonObject) -> JsonObject,
  ): JsonObject {
    val all = views(config)
    require(view in all.indices) { "No view $view" }
    return config.with("views", JsonArray(all.mapIndexed { i, v -> if (i == view) edit(v) else v }))
  }

  private fun updateCards(
    config: JsonObject,
    view: Int,
    section: Int?,
    edit: (List<JsonElement>) -> List<JsonElement>,
  ): JsonObject =
    updateView(config, view) { v ->
      if (section == null) {
        v.with("cards", JsonArray(edit(v.array("cards"))))
      } else {
        val all = v.array("sections")
        require(section in all.indices) { "No section $section" }
        v.with(
          "sections",
          JsonArray(
            all.mapIndexed { i, s ->
              if (i != section) s
              else (s as JsonObject).let { it.with("cards", JsonArray(edit(it.array("cards")))) }
            }
          ),
        )
      }
    }

  private fun <T> List<T>.moved(from: Int, by: Int): List<T> {
    require(from in indices) { "No item $from" }
    val to = (from + by).coerceIn(0, lastIndex)
    return toMutableList().apply { add(to, removeAt(from)) }
  }

  private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
}

/** [DashboardDraft.size]: a card's current size and the range the editor may resize it in. */
data class CardSize(
  val columns: Int,
  val rows: Int?,
  val minColumns: Int,
  val maxColumns: Int,
  val minRows: Int,
  val maxRows: Int,
)

/** This card config as the model reads it; a card without a `type` reads as an empty type. */
fun JsonObject.toCardConfig(): CardConfig =
  CardConfig(type = (this["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(), raw = this)
