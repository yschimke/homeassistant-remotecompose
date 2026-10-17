package ee.schimke.ha.model

import kotlin.math.floor
import kotlin.math.min

/**
 * The layout of a Lovelace `type: sections` view, as the HA frontend computes it in
 * `hui-sections-view.ts`. Pure, so the app's dashboard screen and the preview harness place
 * sections with the same function and cannot drift.
 *
 * HA's rules, reproduced here:
 * - **Columns that fit.** `floor((width + gap) / (columnMinWidth + gap))`, at least 1, with a 320
 *   px minimum column width.
 * - **`max_columns`.** That count is clamped to the view's `max_columns` (default 4).
 * - **No more columns than content.** The grid never has more columns than the sum of the sections'
 *   `column_span`s, so one span-1 section is one column, not one column beside empty ones.
 * - **`column_span`.** Each section spans `min(column_span ?: 1, columns)` columns and `row_span ?:
 *   1` rows.
 * - **Placement.** CSS grid auto-placement in authored order: `grid-auto-flow: row`, or `row dense`
 *   when the view sets `dense_section_placement`. Rows size to their tallest section and sections
 *   align to the top of their row (`align-items: start`); there is no masonry.
 * - **Width cap.** The grid is at most `columns × 500 px` plus gaps wide, centred.
 *
 * One addition HA does not have: when every section ends up spanning the full width (a
 * [SectionsGridLayout.singleTrack] layout, which is what the common `column_span: 4` dashboards
 * produce on a phone or a small tablet), the caller may pass a readable cap ([readableMaxWidthDp]).
 * HA's cards fill such a row with their own 12-column grids; ours do not yet, so without a cap a
 * landscape phone stretches every tile across the screen.
 */
object SectionsGrid {
  /** HA's `DEFAULT_MAX_COLUMNS`. */
  const val DEFAULT_MAX_COLUMNS: Int = 4

  /** HA's `--ha-view-sections-column-min-width` (CSS px ≈ dp). */
  const val COLUMN_MIN_WIDTH_DP: Float = 320f

  /** HA's `--ha-view-sections-column-max-width`. */
  const val COLUMN_MAX_WIDTH_DP: Float = 500f

  /** `hui-view-header`'s `.heading { max-width: 700px }`. */
  const val HEADER_MAX_WIDTH_DP: Float = 700f

  /** Readable single-track cap from Medium width (600 dp) up to Expanded. */
  const val MEDIUM_MAX_CONTENT_WIDTH_DP: Float = 600f

  /** Readable single-track cap from Expanded width (840 dp) up. */
  const val EXPANDED_MAX_CONTENT_WIDTH_DP: Float = 840f

  /**
   * The readable width cap for content that renders as one column, by window width: none on a phone
   * (Compact, below 600 dp), [MEDIUM_MAX_CONTENT_WIDTH_DP] at Medium,
   * [EXPANDED_MAX_CONTENT_WIDTH_DP] at Expanded. Null means no cap.
   */
  fun readableMaxWidthDp(windowWidthDp: Float): Float? =
    when {
      windowWidthDp < 600f -> null
      windowWidthDp < 840f -> MEDIUM_MAX_CONTENT_WIDTH_DP
      else -> EXPANDED_MAX_CONTENT_WIDTH_DP
    }

  /**
   * How many [COLUMN_MIN_WIDTH_DP] columns separated by [columnGapDp] fit in [availableWidthDp].
   */
  fun fittingColumns(availableWidthDp: Float, columnGapDp: Float): Int {
    val fit = floor((availableWidthDp + columnGapDp) / (COLUMN_MIN_WIDTH_DP + columnGapDp)).toInt()
    return fit.coerceAtLeast(1)
  }

  /** Lay out [view]'s sections; see [layout]. */
  fun layout(
    view: View,
    availableWidthDp: Float,
    columnGapDp: Float,
    singleTrackMaxWidthDp: Float? = null,
  ): SectionsGridLayout =
    layout(
      spans = view.sections.map { SectionSpan(it.columnSpan, it.rowSpan) },
      availableWidthDp = availableWidthDp,
      columnGapDp = columnGapDp,
      maxColumns = view.maxColumns,
      dense = view.denseSectionPlacement == true,
      singleTrackMaxWidthDp = singleTrackMaxWidthDp,
    )

  /**
   * Lay out sections with the given authored [spans] in a grid [availableWidthDp] wide.
   *
   * @param singleTrackMaxWidthDp optional readable cap applied only when the result is
   *   [SectionsGridLayout.singleTrack]; see the class docs.
   */
  fun layout(
    spans: List<SectionSpan>,
    availableWidthDp: Float,
    columnGapDp: Float,
    maxColumns: Int? = null,
    dense: Boolean = false,
    singleTrackMaxWidthDp: Float? = null,
  ): SectionsGridLayout {
    val maxColumnCount =
      fittingColumns(availableWidthDp, columnGapDp)
        .coerceAtMost((maxColumns ?: DEFAULT_MAX_COLUMNS).coerceAtLeast(1))
    // HA sums `column_span ?? 1` before clamping each span to the column count.
    val totalSpan = spans.sumOf { it.columnSpanOrDefault }
    val columnCount = min(maxColumnCount, totalSpan).coerceAtLeast(1)
    val placements =
      placeAuto(
        items =
          spans.map { GridSpan(min(it.columnSpanOrDefault, columnCount), it.rowSpanOrDefault) },
        columns = columnCount,
        dense = dense,
      )
    val singleTrack = columnCount == 1 || placements.all { it.columnSpan == columnCount }
    val haMaxWidth = columnCount * COLUMN_MAX_WIDTH_DP + (columnCount - 1) * columnGapDp
    val maxWidth =
      if (singleTrack && singleTrackMaxWidthDp != null) min(haMaxWidth, singleTrackMaxWidthDp)
      else haMaxWidth
    return SectionsGridLayout(
      columnCount = columnCount,
      placements = placements,
      maxWidthDp = maxWidth,
      singleTrack = singleTrack,
    )
  }

  /**
   * CSS grid auto-placement of [items] into a grid of [columns] fixed columns and as many implicit
   * rows as needed (`grid-auto-flow: row`, or `row dense` when [dense]). Spans wider than the grid
   * are clamped to it. Returns one [GridPlacement] per item, in item order.
   *
   * Sparse flow keeps a cursor and never places an item before the previous one; dense flow
   * searches from the top-left for every item, back-filling holes.
   */
  fun placeAuto(items: List<GridSpan>, columns: Int, dense: Boolean = false): List<GridPlacement> {
    require(columns >= 1) { "columns must be >= 1, was $columns" }
    val occupied = mutableSetOf<Long>()
    fun key(row: Int, column: Int): Long = row.toLong() * columns + column
    fun fits(row: Int, column: Int, columnSpan: Int, rowSpan: Int): Boolean {
      if (column + columnSpan > columns) return false
      for (r in row until row + rowSpan) {
        for (c in column until column + columnSpan) if (key(r, c) in occupied) return false
      }
      return true
    }

    var cursorRow = 0
    var cursorColumn = 0
    return items.mapIndexed { index, item ->
      val columnSpan = item.columnSpan.coerceIn(1, columns)
      val rowSpan = item.rowSpan.coerceAtLeast(1)
      var row = if (dense) 0 else cursorRow
      var column = if (dense) 0 else cursorColumn
      while (!fits(row, column, columnSpan, rowSpan)) {
        column++
        if (column + columnSpan > columns) {
          column = 0
          row++
        }
      }
      for (r in row until row + rowSpan) {
        for (c in column until column + columnSpan) occupied += key(r, c)
      }
      cursorRow = row
      cursorColumn = column + columnSpan
      GridPlacement(index, row, column, columnSpan, rowSpan)
    }
  }
}

/** A section's authored spans, before clamping. Null and non-positive values mean 1, as in HA. */
data class SectionSpan(val columnSpan: Int? = null, val rowSpan: Int? = null) {
  val columnSpanOrDefault: Int
    get() = columnSpan?.takeIf { it > 0 } ?: 1

  val rowSpanOrDefault: Int
    get() = rowSpan?.takeIf { it > 0 } ?: 1
}

/** An item to auto-place: how many columns and rows it spans. */
data class GridSpan(val columnSpan: Int = 1, val rowSpan: Int = 1)

/** Where [SectionsGrid.placeAuto] put item [index]: zero-based row and column, and its spans. */
data class GridPlacement(
  val index: Int,
  val row: Int,
  val column: Int,
  val columnSpan: Int,
  val rowSpan: Int,
)

/**
 * The result of [SectionsGrid.layout].
 *
 * @property columnCount column tracks in the grid.
 * @property placements one per section, in authored order.
 * @property maxWidthDp the widest the grid should be drawn; centre it when the host is wider.
 * @property singleTrack every section spans the full width, so the view reads as one column (the
 *   phone layout, with sections stacked).
 */
data class SectionsGridLayout(
  val columnCount: Int,
  val placements: List<GridPlacement>,
  val maxWidthDp: Float,
  val singleTrack: Boolean,
) {
  /** Rows the placements occupy. */
  val rowCount: Int
    get() = placements.maxOfOrNull { it.row + it.rowSpan } ?: 0
}
