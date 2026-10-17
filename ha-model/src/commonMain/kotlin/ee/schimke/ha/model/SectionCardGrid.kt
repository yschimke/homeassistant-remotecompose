package ee.schimke.ha.model

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * How the cards inside one Lovelace `sections` section are laid out, as the HA frontend computes it
 * in `hui-grid-section.ts`, `compute-card-grid-size.ts` and each card's `getGridOptions()`.
 *
 * harc doesn't render sections this way: these sizes describe HA's card designs, and harc lays a
 * section out by its own designs' sizes instead ([SectionCardFlow], design principle 9). This model
 * stays as the reference for what HA does, and for [gridOptions].
 *
 * HA's rules, reproduced here:
 * - **12 columns per section column.** A section's grid has `12 × column_span` equal columns, where
 *   `column_span` is the span the section was given in the view ([SectionsGrid]), so a section
 *   spanning two view columns is a 24-column grid. Columns are separated by [COLUMN_GAP_DP]; the
 *   column count does not change with width, so a 6-column tile is half a phone-wide section.
 * - **Card size.** Each card's `grid_options` (`columns: N | "full"`, `rows: N | "auto"`,
 *   `min_columns`, `max_columns`, `min_rows`, `max_rows`) are laid over the card type's defaults
 *   ([defaultGridOptions]); the older `layout_options` (`grid_columns`, `grid_rows`, …) are read
 *   when `grid_options` is absent, their column values multiplied by [LEGACY_COLUMN_MULTIPLIER].
 *   Numeric `columns` and `rows` are clamped to their min/max. Missing values mean 12 columns and
 *   `auto` rows.
 * - **Spans.** A card spans `min(columns, gridColumns)` columns; `"full"` spans the whole row.
 *   Numeric `rows` spans that many rows, and HA fixes the card's height to `rows × 56 + (rows − 1)
 *   × 8` dp ([fixedHeightDp], carried on each placement); `auto` spans one row at the card's
 *   natural height.
 * - **Placement.** CSS grid auto-placement in authored order with the default `grid-auto-flow: row`
 *   (sparse, not dense), rows sized to their content (`grid-auto-rows: auto`) and separated by
 *   [ROW_GAP_DP].
 */
object SectionCardGrid {
  /** HA's `--base-column-count`. */
  const val BASE_COLUMNS: Int = 12

  /** HA's `--ha-section-grid-row-height`. */
  const val ROW_HEIGHT_DP: Float = 56f

  /** HA's `--ha-section-grid-row-gap`. */
  const val ROW_GAP_DP: Float = 8f

  /** HA's `--ha-section-grid-column-gap`. */
  const val COLUMN_GAP_DP: Float = 8f

  /** HA's `GRID_COLUMN_MULTIPLIER`: legacy `layout_options` columns were on a 4-column grid. */
  const val LEGACY_COLUMN_MULTIPLIER: Int = 3

  /** The height HA gives a card spanning [rows] fixed rows. */
  fun fixedHeightDp(rows: Int): Float = rows * ROW_HEIGHT_DP + (rows - 1) * ROW_GAP_DP

  /**
   * Lay out [cards] (in authored order) in a section spanning [sectionColumnSpan] view columns, so
   * a grid of `12 × sectionColumnSpan` columns.
   */
  fun plan(cards: List<CardConfig>, sectionColumnSpan: Int = 1): SectionCardPlan {
    val columns = BASE_COLUMNS * sectionColumnSpan.coerceAtLeast(1)
    val sizes = cards.map { size(it) }
    val spans = sizes.map { size ->
      GridSpan(
        columnSpan = size.columns?.coerceIn(1, columns) ?: columns,
        rowSpan = size.rows ?: 1,
      )
    }
    val placements =
      SectionsGrid.placeAuto(spans, columns).mapIndexed { i, p ->
        CardCellPlacement(
          index = p.index,
          row = p.row,
          column = p.column,
          columnSpan = p.columnSpan,
          rowSpan = p.rowSpan,
          fixedHeightDp = sizes[i].rows?.let(::fixedHeightDp),
        )
      }
    return SectionCardPlan(columnCount = columns, placements = placements)
  }

  /** The clamped size HA gives [card] (`computeCardGridSize` of the merged grid options). */
  fun size(card: CardConfig): CardGridSize {
    val options = gridOptions(card)
    val columns =
      when (val c = options.columns) {
        GridValue.Full -> null
        is GridValue.Number -> clamp(c.value, options.minColumns, options.maxColumns)
        else -> BASE_COLUMNS
      }
    val rows =
      when (val r = options.rows) {
        is GridValue.Number -> clamp(r.value, options.minRows, options.maxRows)
        else -> null
      }
    return CardGridSize(
      // CSS drops a non-positive span and falls back to span 1.
      columns = columns?.let { if (it < 1) 1 else it },
      rows = rows?.takeIf { it >= 1 },
    )
  }

  /**
   * [card]'s grid options: its type's defaults with the config's `grid_options` (or migrated
   * `layout_options`) laid over them key by key, as `hui-card.getGridOptions()` merges them.
   */
  fun gridOptions(card: CardConfig): CardGridOptions {
    val defaults = defaultGridOptions(card)
    val config = configGridOptions(card.raw) ?: return defaults
    return CardGridOptions(
      columns = config.columns ?: defaults.columns,
      rows = config.rows ?: defaults.rows,
      minColumns = config.minColumns ?: defaults.minColumns,
      maxColumns = config.maxColumns ?: defaults.maxColumns,
      minRows = config.minRows ?: defaults.minRows,
      maxRows = config.maxRows ?: defaults.maxRows,
    )
  }

  /**
   * The grid options a card type's `getGridOptions()` returns in the HA frontend. Types without one
   * (glance, gauge, markdown, light, media-control, alarm-panel, picture*, todo-list, the stacks,
   * conditional, entity-filter, custom cards) return nothing, which HA reads as 12 columns, `auto`
   * rows.
   */
  fun defaultGridOptions(card: CardConfig): CardGridOptions {
    val raw = card.raw
    return when (card.type) {
      CardTypes.TILE -> tileOptions(raw)
      CardTypes.AREA -> areaOptions(raw)
      CardTypes.BUTTON ->
        if (
          raw.bool("show_icon") != false &&
            (raw.bool("show_name") != false || raw.bool("show_state") == true)
        ) {
          options(columns = 6, rows = 2, minColumns = 2, minRows = 2)
        } else {
          options(columns = 3, rows = 1, minColumns = 2, minRows = 1)
        }
      CardTypes.ENTITY,
      CardTypes.SENSOR,
      CardTypes.STATISTIC -> options(columns = 6, rows = 2, minColumns = 6, minRows = 2)
      CardTypes.ENTITIES ->
        CardGridOptions(
          columns = GridValue.Number(12),
          rows = GridValue.Auto,
          minColumns = 3,
        )
      CardTypes.HEADING ->
        CardGridOptions(columns = GridValue.Full, rows = GridValue.Auto, minColumns = 3)
      CardTypes.HISTORY_GRAPH ->
        CardGridOptions(columns = GridValue.Number(12), minColumns = 6, minRows = 2)
      CardTypes.STATISTICS_GRAPH ->
        CardGridOptions(columns = GridValue.Number(12), minColumns = 6, minRows = 3)
      CardTypes.CALENDAR -> options(columns = 12, rows = 6, minColumns = 4, minRows = 4)
      CardTypes.LOGBOOK ->
        options(
          columns = 12,
          rows = 6,
          minColumns = 6,
          minRows = if (raw.string("title") != null) 4 else 3,
        )
      CardTypes.MAP ->
        CardGridOptions(
          columns = GridValue.Full,
          rows = GridValue.Number(4),
          minColumns = 6,
          minRows = 2,
        )
      CardTypes.IFRAME ->
        CardGridOptions(
          columns = GridValue.Full,
          rows = GridValue.Number(4),
          minColumns = 3,
          minRows = 2,
        )
      CardTypes.THERMOSTAT,
      CardTypes.HUMIDIFIER -> {
        val features = raw.array("features")?.size ?: 0
        val featureHeight = if (features > 0) ceil(features * 2 / 3.0).toInt() else 0
        options(columns = 12, rows = 5 + featureHeight, minColumns = 6, minRows = 2 + featureHeight)
      }
      CardTypes.WEATHER_FORECAST -> weatherOptions(raw)
      CardTypes.CLOCK -> clockOptions(raw)
      else -> CardGridOptions()
    }
  }

  private fun tileOptions(raw: JsonObject): CardGridOptions {
    var minColumns = 6
    var rows = 1
    val vertical = raw.bool("vertical") == true
    val position = featurePosition(raw)
    val features = raw.array("features")?.size ?: 0
    if (features > 0) {
      if (position == "inline") minColumns = 12
      rows += featureRows(features, position)
    }
    if (vertical) {
      rows++
      minColumns = 3
    }
    return options(columns = 6, rows = rows, minColumns = minColumns, minRows = rows)
  }

  private fun areaOptions(raw: JsonObject): CardGridOptions {
    var columns = 6
    var minColumns = 6
    var rows = 1
    val position = featurePosition(raw)
    val features = raw.array("features")?.size ?: 0
    if (features > 0) {
      if (position == "inline") {
        minColumns = 12
        columns = 12
      }
      rows += featureRows(features, position)
    }
    if (raw.bool("vertical") == true) {
      rows++
      minColumns = 3
    }
    if ((raw.string("display_type") ?: "picture") != "compact") {
      rows += if (position == "inline" && features > 0) 3 else 2
    }
    return options(columns = columns, rows = rows, minColumns = minColumns, minRows = rows)
  }

  private fun weatherOptions(raw: JsonObject): CardGridOptions {
    val showCurrent = raw.bool("show_current") != false
    val showForecast = raw.bool("show_forecast") != false
    var rows = 1
    var minRows = 1
    if (showCurrent) {
      rows++
      minRows++
    }
    if (showForecast) {
      rows++
      minRows++
      if (raw.string("forecast_type") == "daily") rows++
    }
    return options(
      columns = 12,
      rows = rows,
      minColumns = if (showCurrent && showForecast) 5 else 4,
      minRows = minRows,
    )
  }

  private fun clockOptions(raw: JsonObject): CardGridOptions {
    val titled = raw.string("title") != null
    val size = raw.string("clock_size")
    return if (raw.string("clock_style") == "analog") {
      when (size) {
        "medium" -> options(columns = 6, rows = 3, minColumns = 5, minRows = if (titled) 4 else 3)
        "large" -> options(columns = 6, rows = 4, minColumns = 6, minRows = if (titled) 5 else 4)
        else -> options(columns = 6, rows = 2, minColumns = 2, minRows = if (titled) 3 else 2)
      }
    } else {
      when (size) {
        "medium" ->
          options(
            columns = 6,
            rows = 2,
            minColumns = 4,
            minRows = if (titled) 2 else 1,
            maxRows = 4,
          )
        "large" -> options(columns = 6, rows = 2, minColumns = 6, minRows = 2, maxRows = 4)
        else -> options(columns = 6, rows = 1, minColumns = 3, minRows = 1, maxRows = 4)
      }
    }
  }

  /** `features_position`, forced to `bottom` for a vertical card. */
  private fun featurePosition(raw: JsonObject): String =
    if (raw.bool("vertical") == true) "bottom" else raw.string("features_position") ?: "bottom"

  /**
   * `computeCardFeatureRows`: inline puts the first feature beside the content, the rest below in
   * up to two columns.
   */
  private fun featureRows(features: Int, position: String): Int {
    if (position != "inline") return features
    val below = features - 1
    val columns = minOf(below, 2).coerceAtLeast(1)
    return ceil(below / columns.toDouble()).toInt()
  }

  private fun configGridOptions(raw: JsonObject): CardGridOptions? {
    (raw["grid_options"] as? JsonObject)?.let { o ->
      return CardGridOptions(
        columns = o.gridValue("columns"),
        rows = o.gridValue("rows"),
        minColumns = o.int("min_columns"),
        maxColumns = o.int("max_columns"),
        minRows = o.int("min_rows"),
        maxRows = o.int("max_rows"),
      )
    }
    (raw["layout_options"] as? JsonObject)?.let { o ->
      val m = LEGACY_COLUMN_MULTIPLIER
      return CardGridOptions(
        columns =
          when (val c = o.gridValue("grid_columns")) {
            is GridValue.Number -> GridValue.Number(c.value * m)
            else -> c
          },
        rows = o.gridValue("grid_rows"),
        minColumns = o.int("grid_min_columns")?.times(m),
        maxColumns = o.int("grid_max_columns")?.times(m),
        minRows = o.int("grid_min_rows"),
        maxRows = o.int("grid_max_rows"),
      )
    }
    return null
  }

  private fun options(
    columns: Int,
    rows: Int,
    minColumns: Int? = null,
    minRows: Int? = null,
    maxColumns: Int? = null,
    maxRows: Int? = null,
  ) =
    CardGridOptions(
      columns = GridValue.Number(columns),
      rows = GridValue.Number(rows),
      minColumns = minColumns,
      maxColumns = maxColumns,
      minRows = minRows,
      maxRows = maxRows,
    )

  /** HA's `conditionalClamp`: each bound applies only when set, the max last. */
  private fun clamp(value: Int, min: Int?, max: Int?): Int {
    var result = if (min != null) maxOf(value, min) else value
    if (max != null) result = minOf(result, max)
    return result
  }

  private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

  private fun JsonObject.string(key: String): String? =
    primitive(key)?.takeIf { it.isString }?.content

  private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull

  private fun JsonObject.int(key: String): Int? =
    primitive(key)?.takeIf { !it.isString }?.doubleOrNull?.roundToInt()

  private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

  private fun JsonObject.gridValue(key: String): GridValue? {
    val p = primitive(key) ?: return null
    if (p.isString) {
      return when (p.content) {
        "full" -> GridValue.Full
        "auto" -> GridValue.Auto
        else -> p.content.toDoubleOrNull()?.let { GridValue.Number(it.roundToInt()) }
      }
    }
    return p.doubleOrNull?.let { GridValue.Number(it.roundToInt()) }
  }
}

/** A `columns` or `rows` grid option: a number, `"full"` (columns) or `"auto"` (rows). */
sealed interface GridValue {
  data class Number(val value: Int) : GridValue

  data object Full : GridValue

  data object Auto : GridValue
}

/** HA's `LovelaceGridOptions`; null means unset. */
data class CardGridOptions(
  val columns: GridValue? = null,
  val rows: GridValue? = null,
  val minColumns: Int? = null,
  val maxColumns: Int? = null,
  val minRows: Int? = null,
  val maxRows: Int? = null,
)

/**
 * HA's `CardGridSize` after clamping.
 *
 * @property columns columns to span, or null for `"full"`.
 * @property rows fixed rows to span, or null for `"auto"`.
 */
data class CardGridSize(val columns: Int?, val rows: Int?)

/**
 * Where [SectionCardGrid.plan] put card [index]: zero-based row and column in the section's grid,
 * its spans, and the fixed height its `rows` ask for (null for `auto`, which keeps the card's
 * natural height).
 */
data class CardCellPlacement(
  val index: Int,
  val row: Int,
  val column: Int,
  val columnSpan: Int,
  val rowSpan: Int,
  val fixedHeightDp: Float?,
)

/** The result of [SectionCardGrid.plan]: a [columnCount]-column grid and one placement per card. */
data class SectionCardPlan(val columnCount: Int, val placements: List<CardCellPlacement>) {
  /** Rows the placements occupy. */
  val rowCount: Int
    get() = placements.maxOfOrNull { it.row + it.rowSpan } ?: 0

  /**
   * The width HA gives [placement] in a section [sectionWidthDp] wide, with [columnGapDp] between
   * columns.
   */
  fun cellWidthDp(
    placement: CardCellPlacement,
    sectionWidthDp: Float,
    columnGapDp: Float = SectionCardGrid.COLUMN_GAP_DP,
  ): Float {
    val column = (sectionWidthDp - columnGapDp * (columnCount - 1)).coerceAtLeast(0f) / columnCount
    return column * placement.columnSpan + columnGapDp * (placement.columnSpan - 1)
  }
}
