package ee.schimke.ha.rc.components

import kotlin.math.floor

/**
 * Pure sizing rules for HA's `grid` card: how many cells share a row, given the room the grid has.
 *
 * HA's `columns` is read as the **maximum** per row. Every cell also has a minimum width — the
 * narrowest it can be and still read as its card (see `CardConverter.gridMinCellWidthDp`) — so a
 * grid that is too narrow for `columns` cells at that width lays out fewer, wider ones, wrapping to
 * more rows. Cells in a row are always equal width; if not even one cell fits at its minimum, the
 * grid falls back to one per row.
 */
object HaGridLayout {
  /** Gap between cells, both across a row and between rows, in dp. */
  const val GAP_DP: Int = 8

  /** HA's default `columns` for a `grid` card. */
  const val DEFAULT_COLUMNS: Int = 3

  /**
   * The narrowest width the narrow tier is sized for, in dp: a 360 dp phone minus the dashboard's
   * gutters. Below it a cell may drop under its minimum, as on any surface narrower than its
   * content.
   */
  const val NARROW_REFERENCE_DP: Int = 320

  /**
   * Cells per row in a grid [widthDp] wide: `min(columns, ⌊(width + gap) / (minCell + gap)⌋)`, and
   * at least one.
   */
  fun perRow(widthDp: Float, minCellDp: Int, columns: Int, gapDp: Int = GAP_DP): Int {
    val cap = columns.coerceAtLeast(1)
    if (minCellDp <= 0) return cap
    val fit = floor((widthDp + gapDp) / (minCellDp + gapDp)).toInt()
    return fit.coerceIn(1, cap)
  }

  /** The width [perRow] cells of [minCellDp] need, gaps included, in dp. */
  fun widthFor(perRow: Int, minCellDp: Int, gapDp: Int = GAP_DP): Int =
    perRow * minCellDp + (perRow - 1).coerceAtLeast(0) * gapDp

  /**
   * The two layouts a captured grid carries, and the playback width that picks between them.
   *
   * A grid is captured once and played at whatever width its host gives it, so the per-row count is
   * a playback decision. RemoteCompose's runtime width gates are reliable for a single threshold
   * only (#224), so the grid records two tiers: [widePerRow] cells (all its `columns`, or every
   * card when there are fewer) at or above [thresholdDp], and [narrowPerRow] below it — the most
   * that still fit at their minimum in [NARROW_REFERENCE_DP]. When both are the same there is
   * nothing to switch and [thresholdDp] is null.
   */
  data class Plan(val widePerRow: Int, val narrowPerRow: Int, val thresholdDp: Int?)

  /** The [Plan] for [cellCount] cells of at least [minCellDp], at most [columns] to a row. */
  fun plan(cellCount: Int, columns: Int, minCellDp: Int, gapDp: Int = GAP_DP): Plan {
    val wide = columns.coerceAtLeast(1).coerceAtMost(cellCount.coerceAtLeast(1))
    if (wide == 1) return Plan(1, 1, null)
    val threshold = widthFor(wide, minCellDp, gapDp)
    val narrow =
      perRow(
          widthDp = minOf(NARROW_REFERENCE_DP, threshold - 1).toFloat(),
          minCellDp = minCellDp,
          columns = wide - 1,
          gapDp = gapDp,
        )
        .coerceAtLeast(1)
    return Plan(wide, narrow, threshold)
  }
}

/**
 * HA's `aspect_ratio` option, as width ÷ height: `"16:9"`, `"16x9"`, `"4X3"` and `"1.78"` are
 * ratios; a percentage such as `"50%"` is the height as a share of the width (CSS padding-top), so
 * `"50%"` is 2. Blank, malformed and non-positive values are null.
 */
fun parseHaAspectRatio(value: String?): Float? {
  val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  if (text.endsWith("%")) {
    val percent = text.dropLast(1).trim().toFloatOrNull() ?: return null
    return if (percent > 0f && percent.isFinite()) 100f / percent else null
  }
  val parts = text.split(':', 'x', 'X')
  val ratio =
    when (parts.size) {
      1 -> parts[0].trim().toFloatOrNull()
      2 -> {
        val w = parts[0].trim().toFloatOrNull()
        val h = parts[1].trim().toFloatOrNull()
        if (w == null || h == null || h == 0f) null else w / h
      }
      else -> null
    } ?: return null
  return ratio.takeIf { it > 0f && it.isFinite() }
}

/** The aspect a picture-family card takes when it has no `aspect_ratio`: HA's 16:9 camera frame. */
const val DEFAULT_PICTURE_ASPECT_RATIO: Float = 16f / 9f
