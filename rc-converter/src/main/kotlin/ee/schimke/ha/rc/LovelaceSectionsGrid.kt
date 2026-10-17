package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.SectionsGrid
import ee.schimke.ha.model.SectionsGridLayout

/**
 * Draws a sections view's grid as [SectionsGrid.layout] placed it: [SectionsGridLayout.columnCount]
 * equal columns separated by [columnGap], each child sized to its placement's column span and laid
 * at its row and column. Shared by the app's dashboard screen and the preview harness.
 *
 * Rows behave like HA's CSS grid with `align-items: start`: a row is as tall as its tallest
 * single-row section, sections sit at the top of their row, and a section spanning several rows
 * grows the last of them only when it is taller than the rows it spans together.
 *
 * [content] must emit exactly one child per placement, in placement (authored) order. The grid
 * fills the incoming width; pad it by [SectionsGridLayout.horizontalPaddingDp] and cap it with
 * [SectionsGridLayout.maxWidthDp] in [modifier]. The gaps default to the layout's own (HA's
 * responsive column gap and 24 dp row gap when laid out with [SectionsGrid.layoutInView]).
 */
@Composable
fun LovelaceSectionsGrid(
  grid: SectionsGridLayout,
  modifier: Modifier = Modifier,
  columnGap: Dp = grid.columnGapDp.dp,
  rowGap: Dp = grid.rowGapDp.dp,
  content: @Composable () -> Unit,
) {
  Layout(content = content, modifier = modifier) { measurables, constraints ->
    val placements = grid.placements
    require(measurables.size == placements.size) {
      "LovelaceSectionsGrid expects one child per placement (${placements.size}), " +
        "got ${measurables.size}"
    }
    val columns = grid.columnCount
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val columnGapPx = columnGap.roundToPx()
    val rowGapPx = rowGap.roundToPx()
    val columnWidth = ((width - columnGapPx * (columns - 1)).coerceAtLeast(0)) / columns.toFloat()

    fun spanWidth(span: Int): Int =
      (columnWidth * span + columnGapPx * (span - 1)).toInt().coerceAtLeast(0)

    val placeables: List<Placeable> = measurables.mapIndexed { i, measurable ->
      val w = spanWidth(placements[i].columnSpan)
      measurable.measure(Constraints(minWidth = w, maxWidth = w))
    }

    val rowHeights = IntArray(grid.rowCount)
    placements.forEachIndexed { i, p ->
      if (p.rowSpan == 1) rowHeights[p.row] = maxOf(rowHeights[p.row], placeables[i].height)
    }
    placements.forEachIndexed { i, p ->
      if (p.rowSpan > 1) {
        val last = p.row + p.rowSpan - 1
        var spanned = rowGapPx * (p.rowSpan - 1)
        for (r in p.row..last) spanned += rowHeights[r]
        val missing = placeables[i].height - spanned
        if (missing > 0) rowHeights[last] += missing
      }
    }
    val rowTops = IntArray(rowHeights.size)
    var y = 0
    rowHeights.forEachIndexed { r, h ->
      rowTops[r] = y
      y += h + rowGapPx
    }
    val totalHeight = if (rowHeights.isEmpty()) 0 else y - rowGapPx

    layout(width, totalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
      placements.forEachIndexed { i, p ->
        val x = (p.column * (columnWidth + columnGapPx)).toInt()
        placeables[i].place(x, rowTops[p.row])
      }
    }
  }
}
