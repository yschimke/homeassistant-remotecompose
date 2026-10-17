package ee.schimke.ha.model

/**
 * One card in a section's flow: [minWidth] is the narrowest it still reads as itself in (its
 * design's minimum, not HA's column span), and [ownRow] keeps it on a row of its own. A [hidden]
 * card (a conditional whose conditions don't hold) takes no part in the flow: no row, no width, no
 * gap beside it.
 */
data class SectionFlowItem(
  val minWidth: Float,
  val ownRow: Boolean = false,
  val hidden: Boolean = false,
)

/** One row of a section's flow: the cards' indices, in authored order, and each one's width. */
data class SectionFlowRow(val indices: List<Int>, val widths: List<Float>)

/**
 * How harc lays out the cards inside one Lovelace `sections` section (design principle 9: the
 * dashboard's sections and order, the cards' own sizes).
 *
 * HA sizes a section's cards on its 12-column grid from each card type's `getGridOptions()`. Those
 * sizes describe HA's card designs; harc's designs for the same card differ (a tile with features,
 * a gauge, a button), so a span that fits HA's card can starve or waste space around harc's. The
 * section keeps HA's order and its sections, and lays the cards out by their own minimums instead:
 * - **Rows fill in authored order.** A card joins the current row while every card in it still gets
 *   its [SectionFlowItem.minWidth]; otherwise it starts the next row. Order never changes.
 * - **A row is shared by minimum width.** Each card gets the row's width in proportion to its
 *   minimum, so a list beside a tile gets the larger part, and a card alone fills the row.
 * - **Headings keep a row to themselves** ([ownRow]): they divide a section into groups.
 * - **HA's `grid_options` don't size the flow.** `columns` and `rows` (authored or the card type's
 *   default) are HA's size for HA's design of that card, so they are ignored here, `"full"`
 *   included: dashboards often mark every card full only because HA's spans needed it.
 *
 * Units are whatever the caller uses (dp in tests, px in the layout), as long as they agree.
 */
object SectionCardFlow {
  /**
   * Lay out [items] in a section [width] wide, with [gap] between neighbouring cards. A
   * [SectionFlowItem.hidden] item is in no row.
   */
  fun rows(items: List<SectionFlowItem>, width: Float, gap: Float): List<SectionFlowRow> {
    val rows = mutableListOf<SectionFlowRow>()
    val current = mutableListOf<Int>()
    var used = 0f

    fun flush() {
      if (current.isEmpty()) return
      rows += row(current.toList(), items, width, gap)
      current.clear()
      used = 0f
    }

    items.forEachIndexed { i, item ->
      if (item.hidden) return@forEachIndexed
      if (item.ownRow) {
        flush()
        current += i
        flush()
        return@forEachIndexed
      }
      val needed = if (current.isEmpty()) item.minWidth else used + gap + item.minWidth
      if (current.isNotEmpty() && needed > width) flush()
      used = if (current.isEmpty()) item.minWidth else used + gap + item.minWidth
      current += i
    }
    flush()
    return rows
  }

  /** Whether [card] keeps a row to itself: a heading, which divides the section into groups. */
  fun ownRow(card: CardConfig): Boolean = card.type == CardTypes.HEADING

  private fun row(
    indices: List<Int>,
    items: List<SectionFlowItem>,
    width: Float,
    gap: Float,
  ): SectionFlowRow {
    val room = (width - gap * (indices.size - 1)).coerceAtLeast(0f)
    val total = indices.sumOf { items[it].minWidth.toDouble() }.toFloat()
    val widths =
      if (total <= 0f) indices.map { room / indices.size }
      else indices.map { room * items[it].minWidth / total }
    return SectionFlowRow(indices, widths)
  }
}
