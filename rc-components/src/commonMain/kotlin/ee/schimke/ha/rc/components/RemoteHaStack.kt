@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.layout.remoteComponent
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxHeight
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.width
import ee.schimke.composeai.rccreation.compose.state.RemoteFloat
import ee.schimke.composeai.rccreation.compose.state.RemoteState
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rf

/**
 * Layout wrappers mirroring HA's `vertical-stack`, `horizontal-stack` and `grid` card types. The
 * converter in `:rc-converter` is responsible for emitting each child card.
 */
@Composable
@RemoteComposable
fun RemoteHaVerticalStack(
  modifier: RemoteModifier = RemoteModifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  RemoteColumn(modifier = modifier, verticalArrangement = RemoteArrangement.spacedBy(8.rdp)) {
    content()
  }
}

/**
 * HA `horizontal-stack`: [cellCount] cells side by side, sharing the width equally as HA's `flex: 1
 * 1 0` children do, with [HORIZONTAL_STACK_GAP_DP] between them. Gaps are fixed-width spacer boxes
 * rather than `spacedBy`, for the same reason as [RemoteHaGrid]: the Compose card player shares a
 * weighted row's width before subtracting arrangement spacing. Without weights, a card that fills
 * its width (a grid, an entities list) would take the whole row.
 */
@Composable
@RemoteComposable
fun RemoteHaHorizontalStack(
  cellCount: Int,
  modifier: RemoteModifier = RemoteModifier,
  cell: @Composable @RemoteComposable (index: Int) -> Unit,
) {
  val columns = cellCount.coerceAtLeast(1)
  val name = rememberExpressionName(GridWidthExpressionPrefix)
  RemoteBox(modifier = modifier.fillMaxWidth()) {
    val stackWidthPx =
      RemoteFloat.createNamedRemoteFloat(
        name = name,
        defaultValue = remoteComponent().width,
        domain = RemoteState.Domain.User,
      )
    // Each column's width, from the stack's own width as [RemoteHaGrid] computes its cells': a
    // picture sizes its height from it rather than from what a player reports for a weighted child.
    val cellWidthPx =
      (stackWidthPx - HORIZONTAL_STACK_GAP_DP.rdp.toPx() * (columns - 1).toFloat()) /
        columns.toFloat()
    RemoteText(text = stackWidthPx.toPaddedRemoteString(1), color = Color.Transparent.rc)
    RemoteRow(modifier = RemoteModifier.fillMaxWidth()) {
      CompositionLocalProvider(LocalGridCellWidthPx provides cellWidthPx) {
        for (index in 0 until cellCount) {
          if (index > 0) RemoteBox(modifier = RemoteModifier.width(HORIZONTAL_STACK_GAP_DP.rdp))
          RemoteBox(modifier = RemoteModifier.weight(1f.rf)) { cell(index) }
        }
      }
    }
  }
}

/** The gap between a [RemoteHaHorizontalStack]'s cells, in dp. */
const val HORIZONTAL_STACK_GAP_DP: Int = 8

/**
 * HA `grid` card — [cellCount] cells in rows of [perRow], each row's cells equal width.
 *
 * The rows are ordinary [RemoteRow]s of weighted cells rather than a `RemoteFlowRow`: a flow row
 * packs children by their own width, and a card fills the width it is given, so it wraps one card
 * per line. Which [perRow] applies is the caller's (playback) decision — see [HaGridLayout.Plan].
 *
 * A short last row shares the grid's whole width between its cells when [stretchLastRow] (an odd
 * tile out spans the row rather than sitting beside an empty column); otherwise it keeps the column
 * width, with empty weighted slots after it, as HA's CSS grid does. A grid of pictures keeps the
 * columns, so a picture stays a column's square.
 *
 * Every cell sees its live width through [LocalGridCellWidthPx], computed from the grid's own width
 * — `(width − gaps) ÷ perRow` — so a [RemoteAspectRatioBox] inside a cell is sized from the cell,
 * not from whatever a player reports for a weighted row child.
 *
 * Plain rows need no experimental ops, so the grid renders the same on surfaces that reject
 * `FlowLayout` (`LocalSupportsFlowLayout`).
 *
 * [cell] emits cell `index`; give its root `RemoteModifier.fillMaxWidth()`.
 */
@Composable
@RemoteComposable
fun RemoteHaGrid(
  cellCount: Int,
  perRow: Int,
  modifier: RemoteModifier = RemoteModifier,
  stretchLastRow: Boolean = true,
  fillHeight: Boolean = false,
  cell: @Composable @RemoteComposable (index: Int) -> Unit,
) {
  val columns = perRow.coerceAtLeast(1)
  val name = rememberExpressionName(GridWidthExpressionPrefix)
  RemoteBox(modifier = if (fillHeight) modifier.fillMaxSize() else modifier.fillMaxWidth()) {
    // This box's width: the grid's.
    val gridWidthPx =
      RemoteFloat.createNamedRemoteFloat(
        name = name,
        defaultValue = remoteComponent().width,
        domain = RemoteState.Domain.User,
      )
    val gapPx = HaGridLayout.GAP_DP.rdp.toPx()
    fun cellWidthPx(slots: Int) = (gridWidthPx - gapPx * (slots - 1).toFloat()) / slots.toFloat()
    // Writes the width expression ahead of the cells that read it (it draws nothing).
    RemoteText(text = gridWidthPx.toPaddedRemoteString(1), color = Color.Transparent.rc)
    RemoteColumn(
      modifier = if (fillHeight) RemoteModifier.fillMaxSize() else RemoteModifier.fillMaxWidth(),
      verticalArrangement = RemoteArrangement.spacedBy(HaGridLayout.GAP_DP.rdp),
    ) {
      (0 until cellCount).chunked(columns).forEach { row ->
        // Gaps are fixed-width spacer boxes, not `spacedBy`: the Compose card player shares a
        // weighted row's whole width between its weighted children before arrangement spacing,
        // so with `spacedBy` each cell came out width ÷ perRow and the row overflowed by its gaps.
        // Fixed children are measured first, so the weights split only what the gaps leave.
        // A short last row shares the whole width between its cells rather than leaving empty
        // columns, so an odd tile out doesn't sit beside a hole.
        val slots = if (stretchLastRow) row.size else columns
        val widthPx = cellWidthPx(slots)
        // Filling a fixed height, the rows share it: each cell is a row's height, so its card's
        // own size gate sees the room it really has.
        RemoteRow(
          modifier =
            if (fillHeight) RemoteModifier.fillMaxWidth().weight(1f.rf)
            else RemoteModifier.fillMaxWidth(),
        ) {
          for (slot in 0 until slots) {
            if (slot > 0) RemoteBox(modifier = RemoteModifier.width(HaGridLayout.GAP_DP.rdp))
            val index = row.getOrNull(slot)
            RemoteBox(
              modifier =
                if (fillHeight) RemoteModifier.weight(1f.rf).fillMaxHeight()
                else RemoteModifier.weight(1f.rf),
            ) {
              if (index != null) {
                CompositionLocalProvider(LocalGridCellWidthPx provides widthPx) { cell(index) }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The live width, in px, of the `grid` cell or horizontal-stack column being composed, or null
 * outside one. Read by [RemoteAspectRatioBox]. A [RemoteHaHorizontalStack] provides its own
 * columns' width.
 */
val LocalGridCellWidthPx = staticCompositionLocalOf<RemoteFloat?> { null }

private const val GridWidthExpressionPrefix = "__terrazzo_grid_width_"
