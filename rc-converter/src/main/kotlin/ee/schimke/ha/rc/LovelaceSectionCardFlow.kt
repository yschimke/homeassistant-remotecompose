package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.SectionCardFlow
import ee.schimke.ha.model.SectionCardGrid
import ee.schimke.ha.model.SectionFlowItem

/**
 * The min width (dp) and own-row flag of each of [cards] for [LovelaceSectionCardFlow]: the
 * converter's [CardConverter.gridMinCellWidthDp], so a section sizes cards by harc's designs rather
 * than HA's column spans. An unsupported card asks for a full-width card's 240 dp.
 */
fun sectionFlowItems(
  cards: List<CardConfig>,
  snapshot: HaSnapshot,
  registry: CardRegistry,
): List<SectionFlowItem> = cards.map { card ->
  SectionFlowItem(
    minWidth = (registry.get(card.type)?.gridMinCellWidthDp(card, snapshot) ?: 240).toFloat(),
    ownRow = SectionCardFlow.ownRow(card),
  )
}

/**
 * Draws the cards of one section as [SectionCardFlow] lays them out for the width the section
 * actually has: rows filled in authored order, each card at least its design's minimum and a row's
 * width shared by those minimums (design principle 9). Re-flows whenever the width changes (a
 * rotation, a wider section), so a card's converter always picks its tier from the room it gets.
 * Shared by the app's dashboard screen and the preview harness.
 *
 * Heights wrap: a row is as tall as its tallest card, and shorter cards sit at its top at their
 * natural height (harc's converters wrap their content, so stretching one would leave an empty band
 * inside it).
 *
 * [items] are in dp (see [sectionFlowItems]); [content] must emit exactly one child per item, in
 * the same order.
 */
@Composable
fun LovelaceSectionCardFlow(
  items: List<SectionFlowItem>,
  modifier: Modifier = Modifier,
  columnGap: Dp = SectionCardGrid.COLUMN_GAP_DP.dp,
  rowGap: Dp = SectionCardGrid.ROW_GAP_DP.dp,
  content: @Composable () -> Unit,
) {
  Layout(content = content, modifier = modifier) { measurables, constraints ->
    require(measurables.size == items.size) {
      "LovelaceSectionCardFlow expects one child per item (${items.size}), got ${measurables.size}"
    }
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val columnGapPx = columnGap.roundToPx()
    val rowGapPx = rowGap.roundToPx()
    val pxItems = items.map { it.copy(minWidth = it.minWidth * density) }
    val rows = SectionCardFlow.rows(pxItems, width.toFloat(), columnGapPx.toFloat())

    val placeables = arrayOfNulls<Placeable>(items.size)
    val lefts = IntArray(items.size)
    val tops = IntArray(items.size)
    var y = 0
    rows.forEachIndexed { r, row ->
      if (r > 0) y += rowGapPx
      var x = 0f
      var rowHeight = 0
      row.indices.forEachIndexed { k, i ->
        val left = x.toInt()
        x += row.widths[k]
        // Edges from rounded running sums; the last card ends at the section's edge, so no seam.
        val right = if (k == row.indices.lastIndex) width else x.toInt()
        x += columnGapPx
        val w = (right - left).coerceAtLeast(0)
        val placeable = measurables[i].measure(Constraints(minWidth = w, maxWidth = w))
        placeables[i] = placeable
        lefts[i] = left
        tops[i] = y
        rowHeight = maxOf(rowHeight, placeable.height)
      }
      y += rowHeight
    }

    layout(width, y.coerceIn(constraints.minHeight, constraints.maxHeight)) {
      placeables.forEachIndexed { i, p -> p?.place(lefts[i], tops[i]) }
    }
  }
}
