package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.SectionCardGrid
import ee.schimke.ha.model.SectionCardPlan

/**
 * Draws the cards of one section as [SectionCardGrid.plan] placed them:
 * [SectionCardPlan.columnCount] equal columns (12 per view column the section spans) separated by
 * [columnGap], each card exactly its cell's width, so every card converter picks its width tier
 * from the room HA would give it. Shared by the app's dashboard screen and the preview harness.
 *
 * Heights follow HA's `grid-auto-rows: auto`: a row is as tall as its tallest single-row card, and
 * a card spanning several rows grows the last of them only when it is taller than the rows it spans
 * together. Cards sit at the top of their cell.
 *
 * A card's fixed `rows` shape the placement (it reserves that many rows, so neighbours flow around
 * it as in HA) but not its height: HA stretches a card to
 * [ee.schimke.ha.model.CardCellPlacement.fixedHeightDp], harc's converters wrap their content and
 * do not draw everything HA puts in that height (a tile's features, for one), so forcing HA's
 * height would leave an empty band in the card's cell instead of a card.
 *
 * [content] must emit exactly one child per placement, in placement (authored) order.
 */
@Composable
fun LovelaceSectionCardGrid(
  plan: SectionCardPlan,
  modifier: Modifier = Modifier,
  columnGap: Dp = SectionCardGrid.COLUMN_GAP_DP.dp,
  rowGap: Dp = SectionCardGrid.ROW_GAP_DP.dp,
  content: @Composable () -> Unit,
) {
  Layout(content = content, modifier = modifier) { measurables, constraints ->
    val placements = plan.placements
    require(measurables.size == placements.size) {
      "LovelaceSectionCardGrid expects one child per placement (${placements.size}), " +
        "got ${measurables.size}"
    }
    val columns = plan.columnCount
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val columnGapPx = columnGap.roundToPx()
    val rowGapPx = rowGap.roundToPx()
    val columnWidth = ((width - columnGapPx * (columns - 1)).coerceAtLeast(0)) / columns.toFloat()

    fun left(column: Int): Int = (column * (columnWidth + columnGapPx)).toInt()

    val placeables: List<Placeable> = measurables.mapIndexed { i, measurable ->
      val p = placements[i]
      // Edges from rounded column starts, so neighbouring cells never overlap or leave a seam.
      val right =
        if (p.column + p.columnSpan >= columns) width
        else left(p.column + p.columnSpan) - columnGapPx
      val w = (right - left(p.column)).coerceAtLeast(0)
      measurable.measure(Constraints(minWidth = w, maxWidth = w))
    }
    val cellHeights = IntArray(placements.size) { i -> placeables[i].height }

    val rowHeights = IntArray(plan.rowCount)
    placements.forEachIndexed { i, p ->
      if (p.rowSpan == 1) rowHeights[p.row] = maxOf(rowHeights[p.row], cellHeights[i])
    }
    placements.forEachIndexed { i, p ->
      if (p.rowSpan > 1) {
        val last = p.row + p.rowSpan - 1
        var spanned = rowGapPx * (p.rowSpan - 1)
        for (r in p.row..last) spanned += rowHeights[r]
        val missing = cellHeights[i] - spanned
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
      placements.forEachIndexed { i, p -> placeables[i].place(left(p.column), rowTops[p.row]) }
    }
  }
}
